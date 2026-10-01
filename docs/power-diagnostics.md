# Power and performance diagnostics

OSTT keeps diagnostics off by default and never logs audio or recognized text. Enable **Settings → Debug logging** only while investigating. The bounded private log then records content-free fields such as capture duration, paused duration, inference duration, and inference thread count.

Recording appears as `state=recording`; an explicit native cancellation appears as `state=cancelled`. Neither is an error. Recognition-service failures use `error_code process=service,code=E_RECOGNIZER_<number>`, retaining Android's numeric error code without exception text. A timing record alone does not prove successful recognition: an empty result can still finish computation and then report a no-match error.

Application/keyboard starts, recognition-service creation and enabling logging record `version_code`. Logs survive updates and may contain several builds; a startup marker does not identify older records preceding it. The bounded log can also retain a partial session after trimming. Include the app version and what you observed when sharing a report. Missing error records in an older build do not prove error-free use.

## Quick investigation

1. In **Manage speech models**, run **Benchmark active model** three times with inference threads set to **Automatic**.
2. Repeat with a manual thread count only when comparing a suspected regression. Use the median result; the first run may include model loading and cold caches.
3. Reproduce one ordinary dictation, one Pause/Resume, and one keyboard-to-background transition with debug logging enabled.
4. Confirm that capture ends after transcription, Pause releases the microphone on supported Android audio backends, and the diagnostic durations match the interaction.
5. Use **Save file** to keep a local text snapshot or **Share** to send a temporary read-only snapshot through Android's Sharesheet. Review the destination before sending, then disable and clear the log afterward.
6. For device power rails, profile a release build with Android Studio's Power Profiler while repeating the same fixed workload. Compare traces on the same device, model, temperature, and battery state; Android's battery percentage alone is not a controlled benchmark.

Automatic mode intentionally uses at most two performance cores. A controlled Pixel 6a run with the built-in 11-second sample measured about 225 process CPU ticks in Automatic mode versus 318 at four threads, while median inference latency changed from about 1.0 seconds to 0.9 seconds. Manual higher-thread modes remain available for users who prefer minimum latency over energy use.

Voice input applies automatic silence stopping when enabled, limits unattended interactive capture to ten minutes, and throttles microphone-level and endpoint checks. The optional trailing pause is two seconds by default, with three- and five-second choices for hesitation. A longer choice adds microphone-on waiting time before final transcription; it is not an energy optimization. It applies from the next recording and does not trim captured audio, split decoding at a pause, alter speech detection, or shorten caller-owned audio sessions. Manual recording checks its safety deadline only once per second. The keyboard, popup, and live-subtitle overlay never force the display to stay awake; the foreground app or media player remains responsible for normal screen-timeout behavior.

Capture hot paths keep noise-floor and UI-throttle state on their single audio producer instead of taking cross-thread timing locks for every audio block. Live Subtitles also reuses its JNI transfer allocation and uses a bounded deque for pre-roll, avoiding repeated allocation and front-of-buffer copying during quiet playback.
