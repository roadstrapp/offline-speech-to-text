# Power and performance diagnostics

OSTT keeps diagnostics off by default and never logs audio or recognized text. Enable **More settings → Diagnostic logging** only while investigating. The bounded private log then records content-free fields such as capture duration, paused duration, inference duration, and inference thread count.

## Detailed troubleshooting and battery reports

**Detailed troubleshooting** is a second, default-off opt-in with separate **Enable for 1 hour** and **Enable for 24 hours** confirmation buttons. Basic logging
does not enable it. Confirm its warning to collect session-local random IDs, input
route, per-session build markers, fixed model family and warm-engine state,
setup/decode/final-cleanup/Stop-wait durations, prepared-window counts, process CPU,
battery percentage, plugged state and thermal status. Active sessions sample resource
context at most every 15 seconds without a wake lock. While detailed consent is active, retention is bounded at 1 MiB, so
exports can contain incomplete sessions. Basic logging retains its 64 KiB cap;
the next basic write after detailed consent ends compacts a larger retained log. There are no app identifiers, audio,
transcripts, editor text, dictionary terms, arbitrary model filenames, paths or
exception messages. There is no automatic upload.

The app and keyboard show an active indicator. The lease expires after the explicitly selected one-hour or 24-hour interval;
malformed leases and times outside the consent interval fail closed. An observed
expired or invalid interval is removed and cannot revive after a clock change. Disable it at any time,
or disable basic logging to revoke both. Exports require explicit Save or Share.
Only send them to someone you trust: timing/resource metadata can reveal usage
patterns. Clear deletes retained entries, but active recording can produce new ones;
disable logging before clearing if you want the log to stay empty.

For an initial battery report, no developer options or ADB are needed:

1. Keep one model, language and thread setting. Use a cool, unplugged phone with
   consistent brightness and similar background-app activity.
2. Clear old logs, enable both modes, and repeat the same workload: short and long
   dictation, pauses, Stop, Cancel and the observed failure. Note the model and result.
3. Export promptly, then turn both modes off. Share the metadata file with test
   duration and whether you used the keyboard or White Noise.
4. Compare long dictation with **Prepare long dictation while recording** off/on,
   cooling between runs. Repeat several times and compare medians, not one run.

`cpu_ms` belongs to the whole OSTT process, not exclusively one utterance. Thermal
status is device-wide; battery percentage is coarse and other apps/display/radios
also consume power. None is energy attributable to OSTT. Detailed logging adds its
own overhead. Controlled power-rail/system profiling is a separate optional developer
investigation, not required for the initial report. Unsupported readings remain unknown.

## Safe background preparation

White Noise feeds serial non-overlapping quiet/size-ended caller chunks while its
microphone continues recording. That can hide earlier inference latency, but quiet
cannot prove a sentence ended. OSTT does not copy that strategy into its keyboard.
It retains the complete recording and prepares only windows already forced by the
current non-Whisper runtime's safe long-audio limit. They use the identical quiet-point
search, padding, overlap and stitching as deferred decoding. The last window and all
final corrections wait for Stop. No partial text reaches the editor. Inference remains
serialized; cancellation discards private hypotheses. Failed preparation retains
original audio for final decoding. The existing capture monitor coalesces worker
nudges; there is no extra polling timer or inference in the real-time callback.
Preferred terms and corrections are refreshed at Stop, matching deferred cleanup.
A caught Rust-unwinding preparation panic discards all cached hypotheses and decodes the original
recording from scratch. Neither a failed cache nor a count mismatch may omit words.
This does not protect against a fatal native abort.
Short audio and Whisper do not enqueue preparation work. A native model run cannot
be interrupted mid-window: Cancel suppresses delivery but CPU may continue until
that run returns, and Stop or a new recording may wait for its engine lock.

Basic `timing duration_ms` measures cumulative decode and final cleanup, including
windows prepared during capture—not how long you waited after Stop. Detailed
`stop_wait_ms` measures that wait; `decode_ms` also includes engine-lock contention.
`model_warm=1` means the model was already loaded, not that hardware caches were warm.

The experimental setting is off by default and snapshotted per recording. Short
dictation and Whisper do not decode speculatively. Native limits vary, so there is no
universal activation time. Earlier compute can increase heat during capture even if
final waiting falls. Planning equivalence tests are not acoustic-accuracy or battery
evidence. With unresolved overlap, the existing stitcher preserves both hypotheses;
it cannot promise perfect punctuation or no duplication for arbitrary speech.

Recording appears as `state=recording`; an explicit native cancellation appears as `state=cancelled`. Neither is an error. Recognition-service failures use `error_code process=service,code=E_RECOGNIZER_<number>`, retaining Android's numeric error code without exception text. Event timestamps are captured before asynchronous disk writes; concurrent process records can arrive out of timestamp order. `callback_delivery reason=delivered` means the Android result callback returned, not that an editor inserted the transcript; `reason=callback_failed` means the caller connection failed. Service session end reasons now distinguish callback return (`delivered`) from callback failure (`callback_failed`) instead of using decoded-text reason `text`. A timing record alone does not prove successful recognition: an empty result can still finish computation and then report a no-match error.

Application/keyboard starts, recognition-service creation and enabling logging record `version_code`. Logs survive updates and may contain several builds; a startup marker does not identify older records preceding it. The bounded log can also retain a partial session after trimming. Include the app version and what you observed when sharing a report. Missing error records in an older build do not prove error-free use.

## Quick investigation

1. In **Manage speech models**, run **Benchmark active model** three times with inference threads set to **Automatic**.
2. Repeat with a manual thread count only when comparing a suspected regression. Use the median result; the first run may include model loading and cold caches.
3. Reproduce one ordinary dictation, one Pause/Resume, and one keyboard-to-background transition with Diagnostic logging enabled.
4. Confirm that capture ends after transcription, Pause releases the microphone on supported Android audio backends, and the diagnostic durations match the interaction.
5. Use **Save file** to keep a local text snapshot or **Share** to send a temporary read-only snapshot through Android's Sharesheet. Review the destination before sending, then disable and clear the log afterward.
6. For device power rails, profile a release build with Android Studio's Power Profiler while repeating the same fixed workload. Compare traces on the same device, model, temperature, and battery state; Android's battery percentage alone is not a controlled benchmark.

Automatic mode intentionally uses at most two performance cores. A controlled Pixel 6a run with the built-in 11-second sample measured about 225 process CPU ticks in Automatic mode versus 318 at four threads, while median inference latency changed from about 1.0 seconds to 0.9 seconds. Manual higher-thread modes remain available for users who prefer minimum latency over energy use.

Voice input applies automatic silence stopping when enabled, limits unattended interactive capture to ten minutes, and throttles microphone-level and endpoint checks. The optional trailing pause is two seconds by default, with three- and five-second choices for hesitation. A longer choice adds microphone-on waiting time before final transcription; it is not an energy optimization. It applies from the next recording and does not trim captured audio, split decoding at a pause, alter speech detection, or shorten caller-owned audio sessions. Manual recording checks its safety deadline only once per second. The keyboard, popup, and live-subtitle overlay never force the display to stay awake; the foreground app or media player remains responsible for normal screen-timeout behavior.

Capture hot paths keep noise-floor and UI-throttle state on their single audio producer instead of taking cross-thread timing locks for every audio block. Live Subtitles also reuses its JNI transfer allocation and uses a bounded deque for pre-roll, avoiding repeated allocation and front-of-buffer copying during quiet playback.
