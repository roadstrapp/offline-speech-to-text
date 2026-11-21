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
use std::time::Duration;

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

#[derive(Default)]
struct State {
    buffer: crate::file_audio::Buffer,
    total_samples: u64,
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
            if let Some(window) = state.buffer.take_ready(limit) {
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

    impl Queue {
        /// Test helper: a ready window without blocking.
        fn next_ready(&self, limit: usize) -> Option<Vec<f32>> {
            self.lock().buffer.take_ready(limit)
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
