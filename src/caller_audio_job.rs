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

#[derive(Default)]
struct State {
    buffer: crate::file_audio::Buffer,
    total_samples: u64,
    ended: bool,
    stopped: bool,
}

#[derive(Default)]
pub(crate) struct Queue {
    state: Mutex<State>,
    changed: Condvar,
}

impl Queue {
    pub(crate) fn push(&self, samples: &[f32]) {
        // Bound queued audio even when a caller writes much faster than real time.
        const MAX_QUEUED: usize = 120 * 16_000;
        for chunk in samples.chunks(16_000) {
            let mut state = self.lock();
            while !state.ended && !state.stopped && state.buffer.len() + chunk.len() > MAX_QUEUED {
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
            assert!(state.buffer.len() <= 120 * 16_000);
            if state.buffer.len() == 120 * 16_000 { break; }
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
