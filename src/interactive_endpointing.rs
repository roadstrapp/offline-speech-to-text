use std::time::Duration;

/// Trailing silence after detected speech.
pub(crate) const AUTO_STOP_SILENCE_MS: u64 = 2000;
/// Java validates the private preference; also defend the native boundary.
pub(crate) fn resolve_silence_ms(milliseconds: i32) -> u64 {
    match milliseconds {
        3000 => 3000,
        5000 => 5000,
        _ => AUTO_STOP_SILENCE_MS,
    }
}
/// Timeout after actual microphone samples begin but speech is not detected.
pub(crate) const AUTO_STOP_NO_SPEECH_MS: u64 = 8000;
/// Hard cap even when device-wide microphone access stays disabled. The
/// foreground long-recording service has its own explicit lifecycle.
pub(crate) const MAX_INTERACTIVE_SESSION_MS: u64 = 10 * 60 * 1000;

pub(crate) fn should_finish_interactive_session(
    auto_stop: bool,
    paused: bool,
    audio_started: bool,
    speech_started: bool,
    silence: Duration,
    no_speech: Duration,
    total: Duration,
    trailing_pause_ms: u64,
) -> bool {
    total >= Duration::from_millis(MAX_INTERACTIVE_SESSION_MS)
        || (auto_stop
            && !paused
            && audio_started
            && ((speech_started && silence >= Duration::from_millis(trailing_pause_ms))
                || (!speech_started && no_speech >= Duration::from_millis(AUTO_STOP_NO_SPEECH_MS))))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn longer_pause_preserves_a_hesitation_without_changing_the_default() {
        // Speech at 1s, then a 2.5s thinking pause, then speech at 3.5s.
        // Replay the actual 250ms monitor cadence, without claiming ASR accuracy.
        for (delay, stops_early) in [(2000, true), (3000, false), (5000, false)] {
            let stopped = (1250..3500).step_by(250).any(|now| {
                should_finish_interactive_session(
                    true,
                    false,
                    true,
                    true,
                    Duration::from_millis(now - 1000),
                    Duration::ZERO,
                    Duration::from_millis(now),
                    delay,
                )
            });
            assert_eq!(stopped, stops_early);
        }
        for invalid in [i32::MIN, -1, 0, 1999, 2001, 4000, 8000, i32::MAX] {
            assert_eq!(resolve_silence_ms(invalid), AUTO_STOP_SILENCE_MS);
        }
    }

    #[test]
    fn each_choice_has_an_exact_boundary_and_preserves_other_limits() {
        for delay in [2000, 3000, 5000] {
            assert_eq!(resolve_silence_ms(delay), delay as u64);
            for elapsed in [delay as u64 - 1, delay as u64] {
                assert_eq!(
                    should_finish_interactive_session(
                        true,
                        false,
                        true,
                        true,
                        Duration::from_millis(elapsed),
                        Duration::ZERO,
                        Duration::from_secs(10),
                        delay as u64,
                    ),
                    elapsed >= delay as u64
                );
            }
            assert!(!should_finish_interactive_session(
                false,
                false,
                true,
                true,
                Duration::from_secs(20),
                Duration::from_secs(20),
                Duration::from_secs(20),
                delay as u64,
            ));
            for elapsed in [AUTO_STOP_NO_SPEECH_MS - 1, AUTO_STOP_NO_SPEECH_MS] {
                assert_eq!(
                    should_finish_interactive_session(
                        true,
                        false,
                        true,
                        false,
                        Duration::from_secs(20),
                        Duration::from_millis(elapsed),
                        Duration::from_secs(20),
                        delay as u64,
                    ),
                    elapsed >= AUTO_STOP_NO_SPEECH_MS
                );
            }
            for (paused, audio) in [(true, true), (false, false)] {
                assert!(!should_finish_interactive_session(
                    true,
                    paused,
                    audio,
                    true,
                    Duration::from_secs(30),
                    Duration::from_secs(30),
                    Duration::from_secs(30),
                    delay as u64,
                ));
                assert!(should_finish_interactive_session(
                    false,
                    paused,
                    audio,
                    false,
                    Duration::ZERO,
                    Duration::ZERO,
                    Duration::from_millis(MAX_INTERACTIVE_SESSION_MS),
                    delay as u64,
                ));
            }
        }
    }

    #[test]
    fn silenced_capture_waits_for_real_audio_then_uses_no_speech_timeout() {
        assert!(!should_finish_interactive_session(
            true,
            false,
            false,
            false,
            Duration::ZERO,
            Duration::from_secs(30),
            Duration::from_secs(30),
            AUTO_STOP_SILENCE_MS,
        ));
        assert!(!should_finish_interactive_session(
            true,
            false,
            true,
            false,
            Duration::ZERO,
            Duration::from_millis(AUTO_STOP_NO_SPEECH_MS - 1),
            Duration::from_secs(30),
            AUTO_STOP_SILENCE_MS,
        ));
        assert!(should_finish_interactive_session(
            true,
            false,
            true,
            false,
            Duration::ZERO,
            Duration::from_millis(AUTO_STOP_NO_SPEECH_MS),
            Duration::from_secs(30),
            AUTO_STOP_SILENCE_MS,
        ));
    }

    #[test]
    fn pause_suspends_endpointing_but_hard_cap_still_applies() {
        assert!(should_finish_interactive_session(
            true,
            false,
            true,
            true,
            Duration::from_millis(AUTO_STOP_SILENCE_MS),
            Duration::ZERO,
            Duration::from_secs(5),
            AUTO_STOP_SILENCE_MS,
        ));
        assert!(!should_finish_interactive_session(
            true,
            true,
            true,
            true,
            Duration::from_secs(30),
            Duration::from_secs(30),
            Duration::from_secs(30),
            AUTO_STOP_SILENCE_MS,
        ));
        assert!(should_finish_interactive_session(
            false,
            true,
            false,
            false,
            Duration::ZERO,
            Duration::ZERO,
            Duration::from_millis(MAX_INTERACTIVE_SESSION_MS),
            AUTO_STOP_SILENCE_MS,
        ));
    }
}
