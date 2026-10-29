//! Window scheduling for caller-supplied audio (`EXTRA_AUDIO_SOURCE`).
//!
//! Caller audio opens no microphone, so it does not claim the process-wide
//! capture owner. It is decoded like a file: one bounded model window at a
//! time, each admitted through `INFERENCE_GATE` as background work, so a
//! keyboard or popup final decode that is waiting runs before the next caller
//! window. The reader only appends samples; the decoder takes complete windows
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
        if samples.is_empty() {
            return;
        }
        let mut state = self.lock();
        if state.ended || state.stopped {
            return;
        }
        state.buffer.push(samples);
        state.total_samples = state.total_samples.saturating_add(samples.len() as u64);
        drop(state);
        self.changed.notify_all();
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
