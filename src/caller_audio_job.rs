//! Window scheduling for caller-supplied audio (`EXTRA_AUDIO_SOURCE`).
//!
//! Caller audio opens no microphone, so it does not claim the process-wide
//! capture owner. It is decoded like a file: one bounded model window at a
//! time, each admitted through `INFERENCE_GATE` as background work, so a
//! keyboard or popup final decode that is waiting runs before the next caller
//! window. Only the tail, once the caller has closed its input, is admitted as
//! foreground (see `recog_service::decode_caller_window`). The reader only
//! appends samples; the decoder takes complete windows
//! (keeping the overlap the long-audio reconciliation needs) and, once the
//! input has ended, the tail. Kept free of JNI so the policy runs in host tests.

use std::sync::{Condvar, Mutex};
use std::time::{Duration, Instant};

#[derive(Debug)]
pub(crate) enum Work {
    /// A complete intermediate window to decode.
    Window(Vec<f32>),
    /// Input ended: the remaining audio (possibly empty) and the total length.
    Tail(Vec<f32>, u64),
    /// Cancelled or failed: stop without decoding anything else.
    Stop,
}

/// Pending audio the decoder has not consumed yet. A writer faster than the model (a
/// saved recording, or a live stream with a model slower than real time) is held back
/// here instead of growing memory: two minutes, about 7.7 MB.
pub(crate) const MAX_QUEUED: usize = 120 * 16_000;
/// Room above `MAX_QUEUED` for a Stop drain. Stop reads what is already in the pipe for
/// at most 500 ms; if the queue is full at that moment, waiting for the decoder would
/// outlast that budget and turn the whole dictation into an input error. Thirty seconds
/// of audio (1.9 MB) covers a full 1 MB pipe of 16 kHz PCM16.
pub(crate) const STOP_DRAIN_EXTRA: usize = 30 * 16_000;

// --- Earlier windows for a live stream ----------------------------------------
// A stream that arrives at the speed of speech is someone dictating, and they wait for
// the result from the moment they stop. With windows cut only by the model's bound (up
// to a minute) a dictation shorter than that is decoded entirely after the stop. For a
// live stream a window may therefore end earlier, but only at a real pause, and with
// the same overlap and text reconciliation as every other window. Without such a pause
// nothing is cut early and the model's bound applies as before.

/// Shortest early window, overlap included. Each window after the first repeats three
/// seconds, so a shorter window spends a larger share of the model's time on repeated
/// audio (a third at twelve seconds) and gives the model less context; twelve seconds is
/// still longer than most single dictations to the keyboard. The undecoded rest at the
/// end is then usually between 3 and 20 seconds of audio.
pub(crate) const LIVE_MIN_WINDOW: usize = 12 * 16_000;
/// 20 ms, as in `endpoint.rs`.
const LIVE_FRAME: usize = 320;
/// A pause is at least this long (500 ms). Stop consonants and short hesitations inside
/// a phrase stay below 200 ms; half a second is a breath or the end of a phrase.
const LIVE_PAUSE_FRAMES: usize = 25;
/// The cut lies this far into the pause (200 ms), so the last word has ended and nearly
/// all of the repeated three seconds before it is speech the two texts can be aligned on.
const LIVE_CUT_INTO_PAUSE: usize = 3_200;
/// A pause frame is within this of the quietest tenth of the window...
const LIVE_QUIET_ABOVE_FLOOR_DB: f32 = 8.0;
/// ...and this far below its loudest frames. Where speech does not stand out this clearly
/// from the background, no pause is trusted and nothing is cut early.
const LIVE_QUIET_BELOW_SPEECH_DB: f32 = 15.0;
/// Of the repeated audio before the cut, this much must be speech (1.5 of 3 s): the
/// reconciliation needs shared words, otherwise the texts would only be joined.
const LIVE_OVERLAP_SPEECH_FRAMES: usize = 75;
/// A stream counts as live while its audio is at most 1.25 times the time it took to
/// arrive, plus two seconds for the first buffers. A saved recording written at once is
/// far above that and keeps the model's windows: all of its audio is already here, so
/// earlier windows would only add repeated work.
const LIVE_RATE_PERCENT: u64 = 125;
const LIVE_HEAD_START_MS: u64 = 2_000;

/// Where an early window may end: 200 ms into the latest pause that starts at or after
/// `LIVE_MIN_WINDOW` within the first `limit` samples, if the audio before it is speech.
pub(crate) fn live_cut(samples: &[f32], limit: usize) -> Option<usize> {
    let samples = &samples[..samples.len().min(limit)];
    if samples.len() < LIVE_MIN_WINDOW + LIVE_PAUSE_FRAMES * LIVE_FRAME {
        return None;
    }
    let levels: Vec<f32> = samples
        .chunks_exact(LIVE_FRAME)
        .map(|frame| {
            let energy = frame.iter().map(|x| x * x).sum::<f32>() / LIVE_FRAME as f32;
            10.0 * (energy + 1e-10).log10()
        })
        .collect();
    let mut sorted = levels.clone();
    sorted.sort_by(|a, b| a.total_cmp(b));
    let floor = sorted[(sorted.len() - 1) / 10];
    // Near the loudest frames, so speech still counts when most of the window is a pause.
    let speech = sorted[(sorted.len() - 1) * 99 / 100];
    let quiet_below = (floor + LIVE_QUIET_ABOVE_FLOOR_DB).min(speech - LIVE_QUIET_BELOW_SPEECH_DB);
    if quiet_below <= floor {
        return None;
    }
    let quiet = |frame: usize| levels[frame] < quiet_below;
    let overlap_frames = crate::long_audio::overlap_samples(limit) / LIVE_FRAME;
    let mut end = levels.len();
    while end > 0 {
        if !quiet(end - 1) {
            end -= 1;
            continue;
        }
        let mut start = end - 1;
        while start > 0 && quiet(start - 1) {
            start -= 1;
        }
        let cut = start * LIVE_FRAME + LIVE_CUT_INTO_PAUSE;
        if cut < LIVE_MIN_WINDOW {
            // Earlier pauses lie before the shortest window too.
            return None;
        }
        let spoken = (start.saturating_sub(overlap_frames)..start).filter(|&frame| !quiet(frame)).count();
        if end - start >= LIVE_PAUSE_FRAMES && spoken >= LIVE_OVERLAP_SPEECH_FRAMES {
            return Some(cut);
        }
        end = start;
    }
    None
}

#[derive(Default)]
struct State {
    buffer: crate::file_audio::Buffer,
    total_samples: u64,
    /// When the first and the latest audio arrived, for the live-stream test.
    first_audio: Option<Instant>,
    arrival_ms: u64,
    /// Buffer length at the last search that found no early cut; nothing to repeat
    /// until more audio arrives.
    searched: usize,
    ended: bool,
    stopped: bool,
    /// A caller Stop is draining: `STOP_DRAIN_EXTRA` applies.
    stop_draining: bool,
}

#[derive(Default)]
pub(crate) struct Queue {
    state: Mutex<State>,
    changed: Condvar,
}

impl Queue {
    pub(crate) fn push(&self, samples: &[f32]) {
        // Bound queued audio even when a caller writes much faster than real time.
        for chunk in samples.chunks(16_000) {
            let mut state = self.lock();
            let first = *state.first_audio.get_or_insert_with(Instant::now);
            state.arrival_ms = first.elapsed().as_millis() as u64;
            while !state.ended && !state.stopped && state.buffer.len() + chunk.len() > state.bound() {
                state = self.changed.wait(state).unwrap_or_else(|p| p.into_inner());
            }
            if state.ended || state.stopped { return; }
            state.buffer.push(chunk);
            state.total_samples = state.total_samples.saturating_add(chunk.len() as u64);
            drop(state);
            self.changed.notify_all();
        }
    }

    pub(crate) fn total_samples(&self) -> u64 {
        self.lock().total_samples
    }

    /// The caller stopped: its bounded drain may queue a little past the pending bound,
    /// and a reader waiting for room is released to perform it.
    pub(crate) fn drain_for_stop(&self) {
        self.lock().stop_draining = true;
        self.changed.notify_all();
    }

    /// No more input: what is buffered is decoded, then the tail.
    pub(crate) fn end(&self) {
        self.lock().ended = true;
        self.changed.notify_all();
    }

    /// Cancel or failure: the decoder stops at the next window boundary.
    pub(crate) fn stop(&self) {
        self.lock().stopped = true;
        self.changed.notify_all();
    }

    /// Blocks until there is work. `limit` is the model's window size.
    pub(crate) fn next(&self, limit: usize) -> Work {
        let mut state = self.lock();
        loop {
            if state.stopped {
                return Work::Stop;
            }
            if let Some(window) = state.take_window(limit) {
                self.changed.notify_all();
                return Work::Window(window);
            }
            if state.ended {
                state.stopped = true;
                let total = state.total_samples;
                return Work::Tail(state.buffer.finish(), total);
            }
            state = self
                .changed
                .wait_timeout(state, Duration::from_millis(250))
                .map(|(guard, _)| guard)
                .unwrap_or_else(|p| p.into_inner().0);
        }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|p| p.into_inner())
    }
}

impl State {
    /// Audio arrives about as fast as it is spoken.
    fn live(&self) -> bool {
        let audio_ms = self.total_samples / 16;
        audio_ms <= self.arrival_ms * LIVE_RATE_PERCENT / 100 + LIVE_HEAD_START_MS
    }

    /// The next window to decode, if one is complete. A live stream that is still open
    /// may end a window at a pause; otherwise, and always when there is no such pause,
    /// only the model's bound ends one. Windows are taken when the decoder is free, so
    /// a model that cannot keep up simply gets longer windows.
    fn take_window(&mut self, limit: usize) -> Option<Vec<f32>> {
        if !self.ended && self.live() && self.buffer.len() != self.searched {
            if let Some(cut) = live_cut(self.buffer.samples(), limit) {
                self.searched = 0;
                return Some(self.buffer.take_at(cut, limit));
            }
            self.searched = self.buffer.len();
        }
        let window = self.buffer.take_ready(limit);
        if window.is_some() {
            self.searched = 0;
        }
        window
    }

    fn bound(&self) -> usize {
        if self.stop_draining { MAX_QUEUED + STOP_DRAIN_EXTRA } else { MAX_QUEUED }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    const LIMIT: usize = 20 * 16_000;

    fn speechless(seconds: usize) -> Vec<f32> {
        vec![0.1; seconds * 16_000]
    }

    #[test]
    fn short_input_is_a_single_tail() {
        let queue = Queue::default();
        queue.push(&speechless(5));
        queue.end();
        match queue.next(LIMIT) {
            Work::Tail(audio, total) => {
                assert_eq!(audio.len(), 5 * 16_000);
                assert_eq!(total, 5 * 16_000);
            }
            other => panic!("expected tail, got {other:?}"),
        }
        assert!(matches!(queue.next(LIMIT), Work::Stop));
    }

    #[test]
    fn long_input_is_decoded_window_by_window_before_it_ends() {
        let queue = Queue::default();
        queue.push(&speechless(65));
        // Input is still open, yet complete windows are already available.
        let mut windows = 0;
        let mut covered = 0usize;
        loop {
            match queue.next_ready(LIMIT) {
                Some(window) => {
                    assert!(window.len() <= LIMIT);
                    covered += window.len();
                    windows += 1;
                }
                None => break,
            }
        }
        assert!(windows >= 2, "expected windows before the end, got {windows}");
        queue.end();
        match queue.next(LIMIT) {
            Work::Tail(tail, total) => {
                assert_eq!(total, 65 * 16_000);
                // Windows overlap, so together they cover at least the whole input.
                assert!(covered + tail.len() >= 65 * 16_000);
            }
            other => panic!("expected tail, got {other:?}"),
        }
    }

    #[test]
    fn stop_wins_over_buffered_audio_and_later_input_is_ignored() {
        let queue = Queue::default();
        queue.push(&speechless(65));
        queue.stop();
        assert!(matches!(queue.next(LIMIT), Work::Stop));
        queue.push(&speechless(1));
        assert_eq!(queue.total_samples(), 65 * 16_000);
    }

    #[test]
    fn decoder_waits_for_the_reader_and_wakes_on_end() {
        let queue = Arc::new(Queue::default());
        let decoder = {
            let queue = queue.clone();
            std::thread::spawn(move || queue.next(LIMIT))
        };
        std::thread::sleep(Duration::from_millis(50));
        queue.push(&speechless(2));
        queue.end();
        match decoder.join().unwrap() {
            Work::Tail(audio, total) => {
                assert_eq!(audio.len(), 2 * 16_000);
                assert_eq!(total, 2 * 16_000);
            }
            other => panic!("expected tail, got {other:?}"),
        }
    }

    /// A live stream (100 ms buffers) with its decoder keeping up: every window the
    /// decoder took, then the tail, with the overlap between neighbours checked.
    fn stream(queue: &Queue, audio: &[f32], limit: usize) -> (Vec<Vec<f32>>, Vec<f32>, usize) {
        let mut windows = Vec::new();
        let mut peak = 0;
        for buffer in audio.chunks(1_600) {
            queue.push(buffer);
            peak = peak.max(queue.lock().buffer.len());
            while let Some(window) = queue.next_ready(limit) {
                windows.push(window);
            }
        }
        queue.end();
        match queue.next(limit) {
            Work::Tail(tail, total) => {
                assert_eq!(total, audio.len() as u64);
                (windows, tail, peak)
            }
            other => panic!("expected tail, got {other:?}"),
        }
    }

    /// Rebuilds the stream from the decoded pieces, dropping each piece's leading overlap.
    fn rebuilt(windows: &[Vec<f32>], tail: &[f32], limit: usize) -> Vec<f32> {
        let overlap = crate::long_audio::overlap_samples(limit);
        let mut all: Vec<f32> = Vec::new();
        for (index, piece) in windows.iter().map(Vec::as_slice).chain([tail]).enumerate() {
            if index == 0 {
                all.extend_from_slice(piece);
            } else {
                // The overlap repeats the end of the previous piece exactly.
                assert_eq!(&piece[..overlap], &all[all.len() - overlap..]);
                all.extend_from_slice(&piece[overlap..]);
            }
        }
        all
    }

    /// Distinct, non-quiet samples, so lost, repeated or reordered audio is detected.
    fn speech(seconds: usize, offset: usize) -> Vec<f32> {
        (0..seconds * 16_000).map(|n| 0.2 + ((n + offset) % 9_973) as f32 / 20_000.0).collect()
    }

    #[test]
    fn five_minute_live_stream_is_decoded_as_it_arrives_with_bounded_memory() {
        let limit = 60 * 16_000;
        let audio = speech(300, 0);
        let queue = Queue::default();
        let (windows, tail, peak) = stream(&queue, &audio, limit);
        // Only the tail is left to decode when the writer closes.
        assert!(windows.len() >= 4, "got {} windows", windows.len());
        assert!(tail.len() <= limit);
        assert!(windows.iter().all(|window| window.len() <= limit));
        // A decoder that keeps up holds one window plus the buffer being added.
        assert!(peak <= limit + 1_600, "peak {peak}");
        assert_eq!(rebuilt(&windows, &tail, limit), audio);
    }

    #[test]
    fn twenty_second_pause_ends_nothing_and_loses_no_audio() {
        let limit = 60 * 16_000;
        let mut audio = speech(50, 0);
        let pause = audio.len()..audio.len() + 20 * 16_000;
        audio.extend(vec![0.0; 20 * 16_000]);
        audio.extend(speech(30, 4_000));
        let queue = Queue::default();
        let (windows, tail, _) = stream(&queue, &audio, limit);
        // The pause is not an endpoint. The only cut is the model's one-minute bound,
        // placed at a quiet point inside the pause; the tail starts with the overlap
        // (speech from before the pause) and holds the pause and all speech after it.
        assert_eq!(windows.len(), 1);
        assert!(pause.contains(&windows[0].len()), "cut at {}", windows[0].len());
        let overlap = crate::long_audio::overlap_samples(limit);
        assert!(windows[0].len() - overlap < pause.start);
        assert!(tail.len() > (20 + 30) * 16_000);
        assert_eq!(rebuilt(&windows, &tail, limit), audio);
    }

    #[test]
    fn stop_releases_a_reader_held_by_backpressure_and_keeps_its_audio() {
        let queue = Arc::new(Queue::default());
        queue.push(&speechless(120));
        let (done, completed) = std::sync::mpsc::channel();
        let reader = {
            let queue = queue.clone();
            std::thread::spawn(move || {
                // What the Stop drain still finds in the pipe.
                queue.push(&speechless(2));
                done.send(()).unwrap();
            })
        };
        // Held while the decoder is behind: nothing is dropped, nothing grows.
        assert!(completed.recv_timeout(Duration::from_millis(100)).is_err());
        assert_eq!(queue.total_samples(), 120 * 16_000);
        queue.drain_for_stop();
        completed.recv_timeout(Duration::from_secs(2)).unwrap();
        reader.join().unwrap();
        assert_eq!(queue.total_samples(), 122 * 16_000);
        // The allowance is bounded too: a writer that ignores Stop is held again.
        queue.push(&speechless(28));
        let flood = {
            let queue = queue.clone();
            std::thread::spawn(move || queue.push(&speechless(1)))
        };
        std::thread::sleep(Duration::from_millis(100));
        assert_eq!(queue.total_samples(), (MAX_QUEUED + STOP_DRAIN_EXTRA) as u64);
        queue.stop();
        flood.join().unwrap();
    }

    // --- Live streams: earlier windows at pauses --------------------------------

    const MINUTE: usize = 60 * 16_000;

    /// Deterministic noise in -1..1.
    fn noise(n: usize) -> f32 {
        let x = (n as u32).wrapping_mul(2_654_435_761).rotate_left(13).wrapping_mul(40_503);
        (x >> 8) as f32 / 8_388_608.0 - 1.0
    }

    /// Words of 300 ms with 80 ms gaps over a quiet room; every sample differs.
    fn talk(ms: usize, background: f32, out: &mut Vec<f32>) {
        for _ in 0..ms * 16 {
            let n = out.len();
            let word = n % (380 * 16) < 300 * 16;
            out.push(noise(n) * if word { 0.2 } else { background });
        }
    }

    fn hush(ms: usize, background: f32, out: &mut Vec<f32>) {
        for _ in 0..ms * 16 {
            out.push(noise(out.len()) * background);
        }
    }

    /// A live stream in 100 ms buffers; returns the windows, the tail and each cut's
    /// position in the stream.
    fn live(audio: &[f32], limit: usize) -> (Vec<Vec<f32>>, Vec<f32>, Vec<usize>) {
        let queue = Queue::default();
        let overlap = crate::long_audio::overlap_samples(limit);
        let (mut windows, mut cuts, mut start) = (Vec::new(), Vec::new(), 0usize);
        for (index, buffer) in audio.chunks(1_600).enumerate() {
            queue.push_paced(buffer, (index as u64 + 1) * 100);
            while let Some(window) = queue.next_ready(limit) {
                cuts.push(start + window.len());
                start += window.len() - overlap;
                windows.push(window);
            }
        }
        queue.end();
        match queue.next(limit) {
            Work::Tail(tail, total) => {
                assert_eq!(total, audio.len() as u64);
                assert_eq!(rebuilt(&windows, &tail, limit), audio, "audio lost or repeated");
                (windows, tail, cuts)
            }
            other => panic!("expected tail, got {other:?}"),
        }
    }

    fn rms(samples: &[f32]) -> f32 {
        (samples.iter().map(|x| x * x).sum::<f32>() / samples.len().max(1) as f32).sqrt()
    }

    #[test]
    fn live_dictation_is_cut_only_at_pauses_and_leaves_a_short_tail() {
        // 53 s of speech with a 700 ms pause about every seven seconds.
        let mut audio = Vec::new();
        let mut pauses = Vec::new();
        for _ in 0..7 {
            talk(6_840, 0.002, &mut audio);
            pauses.push(audio.len()..audio.len() + 700 * 16);
            hush(700, 0.002, &mut audio);
        }
        let (windows, tail, cuts) = live(&audio, MINUTE);
        assert!(windows.len() >= 3, "got {} windows", windows.len());
        for (window, cut) in windows.iter().zip(&cuts) {
            assert!(window.len() >= LIVE_MIN_WINDOW && window.len() <= 20 * 16_000, "window {}", window.len());
            // The cut is 200 ms into a pause: after the last word, before the next.
            let pause = pauses.iter().find(|pause| pause.contains(cut)).expect("cut outside a pause");
            assert!(cut - pause.start >= 1_600 && cut - pause.start <= 3_840, "cut {} in {pause:?}", cut);
            // The audio repeated before the cut is speech the two texts share.
            assert!(rms(&audio[cut - 3 * 16_000..cut - 3_840]) > 0.05);
        }
        // What is left to decode when the writer closes.
        assert!(tail.len() <= 20 * 16_000, "tail {}", tail.len());
    }

    #[test]
    fn speech_without_a_pause_is_never_cut_early() {
        let mut audio = Vec::new();
        talk(55_000, 0.002, &mut audio);
        let (windows, tail, _) = live(&audio, MINUTE);
        assert!(windows.is_empty());
        assert_eq!(tail.len(), audio.len());
        // Past the model's bound only that bound cuts, as before.
        talk(15_000, 0.002, &mut audio);
        let (windows, _, cuts) = live(&audio, MINUTE);
        assert_eq!(windows.len(), 1);
        assert!(cuts[0] > 50 * 16_000 && cuts[0] <= MINUTE);
    }

    #[test]
    fn pauses_that_do_not_stand_out_from_the_background_are_not_trusted() {
        // A loud room: the gaps are only 10 dB below the speech.
        let mut audio = Vec::new();
        for _ in 0..7 {
            talk(6_840, 0.06, &mut audio);
            hush(700, 0.06, &mut audio);
        }
        let (windows, _, _) = live(&audio, MINUTE);
        assert!(windows.is_empty());
    }

    #[test]
    fn thinking_pause_stays_inside_one_window_with_speech_on_both_sides() {
        for pause_seconds in [5usize, 20] {
            // "... we should think about" <pause> "the next step ..." in one sentence.
            let mut audio = Vec::new();
            talk(20_000, 0.002, &mut audio);
            let pause = audio.len()..audio.len() + pause_seconds * 16_000;
            hush(pause_seconds * 1_000, 0.002, &mut audio);
            talk(9_000, 0.002, &mut audio);
            let (windows, tail, cuts) = live(&audio, MINUTE);
            // One cut, 200 ms into the pause, never in the middle of the silence.
            assert_eq!(cuts.len(), 1, "{pause_seconds} s pause: cuts {cuts:?}");
            assert!(cuts[0] - pause.start <= 3_840, "cut {} s into the pause", (cuts[0] - pause.start) / 16_000);
            assert_eq!(windows[0].len(), cuts[0]);
            // The next piece holds the words before the pause, the whole pause and the
            // words after it, so the model decides the punctuation with both sides in
            // view and the repeated words replace the end of the first piece.
            let overlap = crate::long_audio::overlap_samples(MINUTE);
            assert!(rms(&tail[..overlap - 3_840]) > 0.05, "no speech before the pause");
            assert!(rms(&tail[overlap + 8_000..overlap + pause_seconds * 16_000 - 8_000]) < 0.01);
            assert!(rms(&tail[tail.len() - 8 * 16_000..]) > 0.05, "no speech after the pause");
        }
    }

    #[test]
    fn stream_that_ends_just_after_a_cut_leaves_the_repeated_words_as_its_tail() {
        let mut audio = Vec::new();
        talk(14_000, 0.002, &mut audio);
        hush(900, 0.002, &mut audio);
        let (windows, tail, cuts) = live(&audio, MINUTE);
        assert_eq!(windows.len(), 1);
        // Three repeated seconds and the rest of the pause; reconciliation drops the repeat.
        assert_eq!(tail.len(), audio.len() - cuts[0] + crate::long_audio::overlap_samples(MINUTE));
        assert!(tail.len() < 4 * 16_000);
    }

    #[test]
    fn recording_written_at_once_keeps_the_models_windows() {
        let mut audio = Vec::new();
        for _ in 0..7 {
            talk(6_840, 0.002, &mut audio);
            hush(700, 0.002, &mut audio);
        }
        // Not paced: all of it arrives within the first moments.
        let queue = Queue::default();
        let (windows, tail, _) = stream(&queue, &audio, MINUTE);
        assert!(windows.is_empty());
        assert_eq!(tail.len(), audio.len());
        // Twice real time is not live either.
        let queue = Queue::default();
        for (index, buffer) in audio.chunks(1_600).enumerate() {
            queue.push_paced(buffer, (index as u64 + 1) * 50);
            assert!(queue.next_ready(MINUTE).is_none());
        }
    }

    #[test]
    fn slow_decoder_gets_longer_windows_instead_of_more_of_them() {
        let mut audio = Vec::new();
        for _ in 0..7 {
            talk(6_840, 0.002, &mut audio);
            hush(700, 0.002, &mut audio);
        }
        // The decoder is free only every 30 s: it takes everything up to the latest pause.
        let queue = Queue::default();
        let mut windows = Vec::new();
        for (index, buffer) in audio.chunks(1_600).enumerate() {
            queue.push_paced(buffer, (index as u64 + 1) * 100);
            if index % 300 == 299 {
                windows.extend(queue.next_ready(MINUTE));
            }
        }
        assert_eq!(windows.len(), 1);
        assert!(windows[0].len() > 25 * 16_000);
    }

    /// What the model returns for each piece, joined as the engine joins them.
    fn joined(pieces: &[&str]) -> (String, usize) {
        let mut text = String::new();
        let mut unaligned = 0;
        for piece in pieces {
            let (next, outcome) = crate::long_audio::stitch_with_join(&text, piece);
            if !text.is_empty() && matches!(outcome, crate::long_audio::StitchOutcome::Fallback { .. }) {
                unaligned += 1;
            }
            text = next;
        }
        (text, unaligned)
    }

    #[test]
    fn cut_inside_a_sentence_leaves_no_trace_in_the_joined_text() {
        let whole = "So for the release I think we should talk about the next step and then decide what to ship.";
        // The first piece ends at the thinking pause. A model that sees the audio end
        // there closes the sentence; the next piece starts with the repeated words,
        // which it may capitalize, and continues the same sentence.
        for (first, second) in [
            ("So for the release I think we should talk about.", "we should talk about the next step and then decide what to ship."),
            ("So for the release I think we should talk about...", "We should talk about the next step and then decide what to ship."),
            ("So for the release I think we should talk about", "Should talk about the next step and then decide what to ship."),
            // A first repeated word heard differently.
            ("So for the release I think we should talk about.", "He should talk about the next step and then decide what to ship."),
        ] {
            let (text, unaligned) = joined(&[first, second]);
            assert_eq!(unaligned, 0, "{first} | {second}");
            assert_eq!(text, whole, "{first} | {second}");
        }
        // A sentence that really ended before the pause keeps its end: the next piece
        // heard the same words and closed the sentence itself.
        let (text, _) = joined(&[
            "That is all we have for the release today.",
            "for the release today. The next step is a new build.",
        ]);
        assert_eq!(text, "That is all we have for the release today. The next step is a new build.");
        // Three pieces, as in a longer dictation.
        let (text, unaligned) = joined(&[
            "First we collect the logs from every phone.",
            "from every phone and then we compare them with.",
            "We compare them with the old build to see what changed.",
        ]);
        assert_eq!(unaligned, 0);
        assert_eq!(text, "First we collect the logs from every phone and then we compare them with the old build to see what changed.");
    }

    impl Queue {
        /// Test helper: a ready window without blocking.
        fn next_ready(&self, limit: usize) -> Option<Vec<f32>> {
            self.lock().take_window(limit)
        }

        /// Test helper: audio that took `arrival_ms` to arrive so far.
        fn push_paced(&self, samples: &[f32], arrival_ms: u64) {
            let mut state = self.lock();
            state.first_audio.get_or_insert_with(Instant::now);
            state.arrival_ms = arrival_ms;
            state.buffer.push(samples);
            state.total_samples += samples.len() as u64;
        }
    }
}

#[cfg(test)]
mod backpressure_tests {
    use super::*;
    use std::sync::{Arc, mpsc};
    #[test]
    fn fast_writer_is_bounded_and_cancel_releases_it() {
        let queue = Arc::new(Queue::default());
        let writer = queue.clone();
        let (done, completed) = mpsc::channel();
        let thread = std::thread::spawn(move || { writer.push(&vec![0.1; 121 * 16_000]); done.send(()).unwrap(); });
        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        loop {
            let state = queue.lock();
            assert!(state.buffer.len() <= MAX_QUEUED);
            if state.buffer.len() == MAX_QUEUED { break; }
            drop(state);
            assert!(std::time::Instant::now() < deadline);
            std::thread::yield_now();
        }
        assert!(completed.try_recv().is_err());
        queue.stop();
        completed.recv_timeout(Duration::from_secs(2)).unwrap();
        thread.join().unwrap();
    }
}

#[cfg(all(test, target_os = "linux"))]
mod caller_end_tests {
    use super::*;
    use crate::caller_audio_input::pipe_tests::{kernel_poll, pair, stopped};
    use crate::caller_audio_input::{
        drain, end_input, end_input_on_cap, ended_by_caller, request_stop, wait_ready_or_stop,
        EndReason, InputEnd, SourceState,
    };
    use std::io::Write;
    use std::os::fd::AsRawFd;
    use std::sync::atomic::{AtomicBool, AtomicU64, AtomicU8, AtomicUsize, Ordering};
    use std::sync::{mpsc, Arc};

    impl Queue {
        fn ended(&self) -> bool {
            self.lock().ended
        }
    }

    /// Releases the real queue's tail, checking the shared end already published its
    /// reason and closed the input, as the decoder's tail priority relies on.
    fn release(queue: &Queue, reason: &AtomicU8, closed: &AtomicBool, releases: &AtomicUsize) {
        assert_ne!(reason.load(Ordering::SeqCst), 0, "tail released before the reason");
        assert!(closed.load(Ordering::SeqCst), "tail released before the input closed");
        releases.fetch_add(1, Ordering::SeqCst);
        queue.end();
    }

    #[test]
    fn cap_tick_during_a_paused_stop_drain_keeps_the_pcm_and_one_foreground_tail() {
        let (reader, mut writer) = pair();
        let pcm: Vec<u8> = (0..4096u32).map(|n| (n % 251) as u8).collect();
        writer.write_all(&pcm).unwrap();
        let fd = reader.as_raw_fd();
        let queue = Arc::new(Queue::default());
        let stop_after_ms = Arc::new(AtomicU64::new(u64::MAX));
        let end_reason = Arc::new(AtomicU8::new(0));
        let input_closed = Arc::new(AtomicBool::new(false));
        let releases = Arc::new(AtomicUsize::new(0));
        assert!(request_stop(&stop_after_ms, 500));

        let (paused_tx, paused_rx) = mpsc::channel();
        let (resume_tx, resume_rx) = mpsc::channel::<()>();
        let reader_thread = {
            let (queue, stop, reason, closed, releases) = (
                queue.clone(),
                stop_after_ms.clone(),
                end_reason.clone(),
                input_closed.clone(),
                releases.clone(),
            );
            std::thread::spawn(move || {
                let mut paused = false;
                let mut received = Vec::new();
                let end = drain(
                    reader,
                    || {
                        wait_ready_or_stop(
                            &stop,
                            || {
                                // Pause the reader right after Stop's claim, before it reads.
                                if !paused {
                                    paused = true;
                                    paused_tx.send(()).unwrap();
                                    resume_rx.recv().unwrap();
                                }
                                SourceState {
                                    finalized: closed.load(Ordering::SeqCst),
                                    stop_after_ms: stop.load(Ordering::SeqCst),
                                    ..stopped()
                                }
                            },
                            |t| kernel_poll(fd, t),
                        )
                    },
                    |bytes| {
                        received.extend_from_slice(bytes);
                        // One sample per byte keeps PCM framing out of this test.
                        let samples: Vec<f32> = bytes.iter().map(|&b| b as f32 / 255.0).collect();
                        queue.push(&samples);
                    },
                )
                .unwrap();
                if matches!(end, InputEnd::Drained) {
                    end_input(&reason, &closed, EndReason::Stopped, || {
                        release(&queue, &reason, &closed, &releases)
                    });
                }
                (end, received)
            })
        };

        paused_rx.recv().unwrap();
        assert!(kernel_poll(fd, 0).unwrap(), "PCM is readable when the cap ticks");
        let capped = end_input_on_cap(&stop_after_ms, &end_reason, &input_closed, || {
            release(&queue, &end_reason, &input_closed, &releases)
        });
        // Stop owns the claim: the cap tick releases nothing and leaves the input open.
        assert!(!capped);
        assert_eq!(releases.load(Ordering::SeqCst), 0);
        assert!(!queue.ended());
        assert!(!input_closed.load(Ordering::SeqCst));
        assert_eq!(end_reason.load(Ordering::SeqCst), 0);
        resume_tx.send(()).unwrap();

        let (end, received) = reader_thread.join().unwrap();
        assert!(matches!(end, InputEnd::Drained));
        assert_eq!(received, pcm);
        assert_eq!(releases.load(Ordering::SeqCst), 1);
        match queue.next(20 * 16_000) {
            Work::Tail(audio, total) => {
                assert_eq!(total, pcm.len() as u64);
                assert_eq!(audio.len(), pcm.len());
            }
            other => panic!("expected tail, got {other:?}"),
        }
        assert!(matches!(queue.next(20 * 16_000), Work::Stop));
        // The decoder admits the tail as foreground only when the caller ended the input.
        assert_eq!(end_reason.load(Ordering::SeqCst), EndReason::Stopped as u8);
        assert!(ended_by_caller(&end_reason));
        drop(writer);
    }
}
