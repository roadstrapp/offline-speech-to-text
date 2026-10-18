# Power and performance diagnostics

OSTT keeps diagnostics off by default and excludes audio and recognized text from logs. Enable **More settings → Diagnostic logging** only while investigating. The bounded private log then records content-free fields such as capture duration, paused duration, inference duration, and inference thread count.

## Detailed troubleshooting and battery reports

**Detailed troubleshooting** is a second, default-off opt-in with an ordered choice of **Enable for 1 hour**, **Enable for 24 hours**, **Enable forever**, then **Cancel**. Detailed troubleshooting requires its own consent after basic logging is enabled. Confirm its warning to collect session-local random IDs, input
route, per-session build markers, fixed model family and warm-engine state,
setup/decode/final-cleanup/Stop-wait durations, prepared-window counts, process CPU,
battery percentage, plugged state and thermal status. Active sessions sample resource
context at most every 15 seconds using ordinary scheduling; wake locks are excluded. While detailed consent is active, retention is bounded at 1 MiB, so
exports can contain incomplete sessions. Basic logging retains its 64 KiB cap;
the next basic write after detailed consent ends compacts a larger retained log. Excluded fields include app identifiers, audio, transcripts, editor text, dictionary terms, arbitrary model filenames, paths and exception messages. Export requires an explicit action.

The app and keyboard show an active indicator. Timed consent expires after the explicitly selected one-hour or 24-hour interval; **Enable forever** stays active until explicitly disabled.
Malformed leases and times outside the consent interval fail closed. Moving the device clock before the consent start time also revokes forever consent. An observed
expired or invalid timed interval is removed and stays removed after a clock change. Disable it at any time,
or disable basic logging to revoke both. Exports require explicit Save or Share.
Only send them to someone you trust: timing/resource metadata can reveal usage
patterns. Clear deletes retained entries, but active recording can produce new ones;
disable logging before clearing if you want the log to stay empty.

An initial battery report uses the app settings:

1. Keep one model, language and thread setting. Use a cool, unplugged phone with
   consistent brightness and similar background-app activity.
2. Clear old logs, enable both modes, and repeat the same workload: short and long
   dictation, pauses, Stop, Cancel and the observed failure, noting the model and result.
3. Export promptly, then turn both modes off. Share the metadata file with test
   duration and whether you used the keyboard or White Noise.
4. Compare long dictation with **Prepare long dictation while recording** off/on,
   cooling between runs. Repeat several times and compare medians across runs.

`cpu_ms` belongs to the whole OSTT process, including concurrent activity. Thermal
status is device-wide; battery percentage is coarse and other apps/display/radios
also consume power. Attributing energy use to OSTT requires controlled profiling. Detailed logging adds its
own overhead. Controlled power-rail/system profiling is a separate optional developer
investigation; the initial report uses the app export. Unsupported readings remain unknown.

## Safe background preparation

White Noise feeds serial non-overlapping quiet/size-ended caller chunks while its
microphone continues recording. That can hide earlier inference latency, but quiet
is only a heuristic for sentence boundaries. The OSTT keyboard uses a different strategy.
It retains the complete recording and prepares only windows already forced by the
current non-Whisper runtime's safe long-audio limit. They use the identical quiet-point
search, padding, overlap and stitching as deferred decoding. The last window and all
final corrections wait for Stop. Editor delivery waits for the final result. Inference remains
serialized; cancellation discards private hypotheses. Failed preparation retains
original audio for final decoding. The existing capture monitor coalesces worker
nudges; polling timers and inference stay outside the real-time callback.
Preferred terms and corrections are refreshed at Stop, matching deferred cleanup.
A caught Rust-unwinding preparation panic discards all cached hypotheses and decodes the original
recording from scratch. Cache failures and count mismatches fall back to complete audio.
Fatal native aborts remain outside Rust unwind recovery.
Short audio and Whisper use deferred decoding only. A native model run completes
its current window: Cancel suppresses delivery but CPU may continue until
that run returns, and Stop or a new recording may wait for its engine lock.

Basic `timing duration_ms` measures cumulative decode and final cleanup, including
windows prepared during capture. Stop latency is recorded separately. Detailed
`stop_wait_ms` measures that wait; `decode_ms` also includes engine-lock contention.
`model_warm=1` reports model loading state only; hardware cache state remains unknown.

The experimental setting is off by default and snapshotted per recording. Short
dictation and Whisper use deferred decoding. Activation time depends on the native limit. Earlier compute can increase heat during capture even if
final waiting falls. Planning equivalence tests establish window coverage; accuracy and battery use require separate evidence. With unresolved overlap, the existing stitcher preserves both hypotheses;
punctuation and duplication still depend on the input and model.

Recording appears as `state=recording`; an explicit native cancellation appears as `state=cancelled`. Both are ordinary lifecycle states. Recognition-service failures use `error_code process=service,code=E_RECOGNIZER_<number>`, retaining Android's numeric error code with exception text excluded. Event timestamps are captured before asynchronous disk writes; concurrent process records can arrive out of timestamp order. `callback_delivery reason=delivered` means the Android result callback returned; editor insertion requires separate confirmation; `reason=callback_failed` means the caller connection failed. Service session end reasons now distinguish callback return (`delivered`) from callback failure (`callback_failed`); the decoded-text reason `text` is reserved for recognition outcomes. A timing record records completed computation: an empty result can still finish computation and then report a no-match error.

Application/keyboard starts, recognition-service creation and enabling logging record `version_code`. Logs survive updates and may contain several builds; a startup marker identifies following records only. The bounded log can also retain a partial session after trimming. Include the app version and what you observed when sharing a report. Older builds may have incomplete error reporting.

## Quick investigation

1. In **Manage speech models**, run **Benchmark active model** three times with inference threads set to **Automatic**.
2. Repeat with a manual thread count only when comparing a suspected regression. Use the median result; the first run may include model loading and cold caches.
3. Reproduce one ordinary dictation, one Pause/Resume, and one keyboard-to-background transition with Diagnostic logging enabled.
4. Confirm that capture ends after transcription, Pause releases the microphone on supported Android audio backends, and the diagnostic durations match the interaction.
5. Use **Save file** to keep a local text snapshot or **Share** to send a temporary read-only snapshot through Android's Sharesheet. Review the destination before sending, then disable and clear the log afterward.
6. For device power rails, profile a release build with Android Studio's Power Profiler while repeating the same fixed workload. Compare traces on the same device, model, temperature, and battery state; Android's battery percentage is coarse context and requires controlled comparison.

Automatic mode intentionally uses at most two performance cores. A controlled Pixel 6a run with the built-in 11-second sample measured about 225 process CPU ticks in Automatic mode versus 318 at four threads, while median inference latency changed from about 1.0 seconds to 0.9 seconds. Manual higher-thread modes remain available for users who prefer minimum latency over energy use.

Voice input applies automatic silence stopping when enabled, limits unattended interactive capture to ten minutes, and throttles microphone-level and endpoint checks. The optional trailing pause is two seconds by default, with three- and five-second choices for hesitation. A longer choice adds microphone-on waiting time before final transcription; it increases capture time. It applies from the next recording. Captured audio, decode boundaries, speech detection and caller-owned session lengths retain their existing behavior. Manual recording checks its safety deadline only once per second. The keyboard, popup, and live-subtitle overlay allow normal display sleep; the foreground app or media player remains responsible for normal screen-timeout behavior.

Capture hot paths keep noise-floor and UI-throttle state on their single audio producer, avoiding cross-thread timing locks for each audio block. Live Subtitles also reuses its JNI transfer allocation and uses a bounded deque for pre-roll, avoiding repeated allocation and front-of-buffer copying during quiet playback.
