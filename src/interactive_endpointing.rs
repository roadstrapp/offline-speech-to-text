use std::time::Duration;

/// Trailing silence after detected speech.
pub(crate) const AUTO_STOP_SILENCE_MS: u64 = 2000;
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
) -> bool {
    total >= Duration::from_millis(MAX_INTERACTIVE_SESSION_MS)
        || (auto_stop
            && !paused
            && audio_started
            && ((speech_started && silence >= Duration::from_millis(AUTO_STOP_SILENCE_MS))
                || (!speech_started && no_speech >= Duration::from_millis(AUTO_STOP_NO_SPEECH_MS))))
}

#[cfg(test)]
mod tests {
    use super::*;

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
        ));
        assert!(!should_finish_interactive_session(
            true,
            false,
            true,
            false,
            Duration::ZERO,
            Duration::from_millis(AUTO_STOP_NO_SPEECH_MS - 1),
            Duration::from_secs(30),
        ));
        assert!(should_finish_interactive_session(
            true,
            false,
            true,
            false,
            Duration::ZERO,
            Duration::from_millis(AUTO_STOP_NO_SPEECH_MS),
            Duration::from_secs(30),
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
        ));
        assert!(!should_finish_interactive_session(
            true,
            true,
            true,
            true,
            Duration::from_secs(30),
            Duration::from_secs(30),
            Duration::from_secs(30),
        ));
        assert!(should_finish_interactive_session(
            false,
            true,
            false,
            false,
            Duration::ZERO,
            Duration::ZERO,
            Duration::from_millis(MAX_INTERACTIVE_SESSION_MS),
        ));
    }
}
