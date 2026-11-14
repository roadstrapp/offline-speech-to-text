//! Liveness of one Bluetooth microphone stream, independent of JNI and AAudio.
//!
//! A Bluetooth stream first goes through the LE Audio setup (no frames or
//! exact zeros for up to a few seconds), then delivers sound. One that never
//! delivers sound, or stops delivering after it did, is dead; the audio
//! callback records arrival times here and a watchdog asks [`verdict`].

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::Instant;

/// Until a stream has delivered its first non-zero sample it is starting:
/// on LE Audio the conversational stream (with the aid's microphone) is only
/// set up when recording starts, which took 1.7 to 3.4 s on hearing aids;
/// meanwhile AudioRecord delivers nothing and AAudio delivers zeros.
/// Declaring the stream dead inside that window, and reopening it, restarted
/// the setup and never let it finish. Only after this long without any sound
/// is a starting stream dead.
pub(crate) const STARTUP_MS: u64 = 8_000;
/// Sound arrived, then no frames for this long: the stream stopped.
pub(crate) const STALL_MS: u64 = 1_000;
/// Sound arrived, then only exact zeros for this long. A live microphone
/// never produces seconds of exact zeros.
pub(crate) const ZERO_MS: u64 = 3_000;

const NONE: u64 = u64::MAX;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Dead {
    NoFrames,
    Stalled,
    Zeros,
    StreamError,
}

impl Dead {
    pub(crate) fn label(self) -> &'static str {
        match self {
            Dead::NoFrames => "no frames after start",
            Dead::Stalled => "frames stopped",
            Dead::Zeros => "only digital zeros",
            Dead::StreamError => "stream error callback",
        }
    }
}

/// Times in milliseconds since the same origin.
#[derive(Debug, Clone, Copy)]
pub(crate) struct Snapshot {
    pub now: u64,
    pub started: u64,
    #[allow(dead_code)]
    pub first_frame: Option<u64>,
    pub last_frame: Option<u64>,
    pub last_nonzero: Option<u64>,
    pub error: bool,
}

pub(crate) fn verdict(s: &Snapshot) -> Option<Dead> {
    if s.error {
        return Some(Dead::StreamError);
    }
    let since_start = s.now.saturating_sub(s.started);
    let last_frame = s.last_frame.filter(|t| *t >= s.started);
    let Some(sound) = s.last_nonzero.filter(|t| *t >= s.started) else {
        // Still starting.
        if since_start < STARTUP_MS {
            return None;
        }
        return Some(if last_frame.is_some() { Dead::Zeros } else { Dead::NoFrames });
    };
    if s.now.saturating_sub(last_frame.unwrap_or(sound)) >= STALL_MS {
        return Some(Dead::Stalled);
    }
    (s.now.saturating_sub(sound) >= ZERO_MS).then_some(Dead::Zeros)
}

/// Written by the audio callback, read by the watchdog.
pub(crate) struct Health {
    origin: Instant,
    started: AtomicU64,
    first_frame: AtomicU64,
    last_frame: AtomicU64,
    last_nonzero: AtomicU64,
    frames: AtomicU64,
    error: AtomicBool,
}

impl Health {
    pub(crate) fn new() -> Self {
        Self {
            origin: Instant::now(),
            started: AtomicU64::new(0),
            first_frame: AtomicU64::new(NONE),
            last_frame: AtomicU64::new(NONE),
            last_nonzero: AtomicU64::new(NONE),
            frames: AtomicU64::new(0),
            error: AtomicBool::new(false),
        }
    }

    pub(crate) fn now_ms(&self) -> u64 {
        self.origin.elapsed().as_millis() as u64
    }

    /// A stream was (re)started or resumed: judge it from now on.
    pub(crate) fn restart(&self) {
        self.started.store(self.now_ms(), Ordering::SeqCst);
        self.first_frame.store(NONE, Ordering::SeqCst);
        self.error.store(false, Ordering::SeqCst);
    }

    pub(crate) fn record(&self, samples: &[f32]) {
        if samples.is_empty() {
            return;
        }
        let now = self.now_ms();
        let _ = self
            .first_frame
            .compare_exchange(NONE, now, Ordering::Relaxed, Ordering::Relaxed);
        self.last_frame.store(now, Ordering::Relaxed);
        self.frames.fetch_add(samples.len() as u64, Ordering::Relaxed);
        if samples.iter().any(|x| *x != 0.0) {
            self.last_nonzero.store(now, Ordering::Relaxed);
        }
    }

    pub(crate) fn mark_error(&self) {
        self.error.store(true, Ordering::SeqCst);
    }

    pub(crate) fn frames(&self) -> u64 {
        self.frames.load(Ordering::Relaxed)
    }

    pub(crate) fn first_frame_ms(&self) -> Option<u64> {
        some(self.first_frame.load(Ordering::Relaxed))
    }

    pub(crate) fn snapshot(&self) -> Snapshot {
        Snapshot {
            now: self.now_ms(),
            started: self.started.load(Ordering::SeqCst),
            first_frame: some(self.first_frame.load(Ordering::Relaxed)),
            last_frame: some(self.last_frame.load(Ordering::Relaxed)),
            last_nonzero: some(self.last_nonzero.load(Ordering::Relaxed)),
            error: self.error.load(Ordering::SeqCst),
        }
    }
}

fn some(value: u64) -> Option<u64> {
    (value != NONE).then_some(value)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn snap(now: u64, first: Option<u64>, last: Option<u64>, nonzero: Option<u64>) -> Snapshot {
        Snapshot {
            now,
            started: 1_000,
            first_frame: first,
            last_frame: last,
            last_nonzero: nonzero,
            error: false,
        }
    }

    #[test]
    fn a_starting_stream_gets_the_whole_le_audio_setup_window() {
        // Nothing yet, or only zeros: still starting until STARTUP_MS.
        assert_eq!(verdict(&snap(1_000 + 3_500, None, None, None)), None);
        assert_eq!(verdict(&snap(1_000 + STARTUP_MS - 1, Some(2_000), Some(8_900), None)), None);
        assert_eq!(
            verdict(&snap(1_000 + STARTUP_MS, None, None, None)),
            Some(Dead::NoFrames)
        );
        assert_eq!(
            verdict(&snap(1_000 + STARTUP_MS, Some(2_000), Some(8_990), None)),
            Some(Dead::Zeros)
        );
        // A gap in frames before the first sound is part of the setup.
        assert_eq!(verdict(&snap(6_000, Some(1_500), Some(2_000), None)), None);
    }

    #[test]
    fn frames_from_before_a_restart_do_not_count() {
        // A previous stream's sound at 900 says nothing about this one.
        assert_eq!(
            verdict(&snap(1_000 + STARTUP_MS, Some(500), Some(900), Some(900))),
            Some(Dead::NoFrames)
        );
    }

    #[test]
    fn a_stream_that_stops_after_sound_is_dead() {
        let s = snap(5_000, Some(4_000), Some(5_000 - STALL_MS), Some(5_000 - STALL_MS));
        assert_eq!(verdict(&s), Some(Dead::Stalled));
        let s = snap(5_000, Some(4_000), Some(4_900), Some(4_900));
        assert_eq!(verdict(&s), None);
    }

    #[test]
    fn sustained_zeros_after_sound_are_dead_but_sound_resets_the_clock() {
        let now = 20_000;
        let s = snap(now, Some(1_100), Some(now - 10), Some(now - ZERO_MS));
        assert_eq!(verdict(&s), Some(Dead::Zeros));
        let s = snap(now, Some(1_100), Some(now - 10), Some(now - ZERO_MS + 1));
        assert_eq!(verdict(&s), None);
    }

    #[test]
    fn an_error_callback_is_dead_at_once() {
        let mut s = snap(1_001, None, None, None);
        s.error = true;
        assert_eq!(verdict(&s), Some(Dead::StreamError));
    }

    #[test]
    fn health_records_arrival_and_sound() {
        let health = Health::new();
        health.restart();
        health.record(&[0.0; 160]);
        let s = health.snapshot();
        assert!(s.first_frame.is_some() && s.last_frame.is_some());
        assert!(s.last_nonzero.is_none());
        health.record(&[0.0, 0.25]);
        assert!(health.snapshot().last_nonzero.is_some());
        assert_eq!(health.frames(), 162);
        health.mark_error();
        assert!(health.snapshot().error);
        health.restart();
        assert!(!health.snapshot().error && health.first_frame_ms().is_none());
    }
}
