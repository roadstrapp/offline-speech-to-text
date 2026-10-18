# Android integration

Offline Speech to Text exposes four Android contracts. Choose the contract before recording; each has a separate owner and lifecycle.

## Choose a surface

| Contract | UI owner | Microphone owner | Result |
|---|---|---|---|
| `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` | Provider activity | Provider | Activity result in `RecognizerIntent.EXTRA_RESULTS` |
| `SpeechRecognizer` bound to an eligible `RecognitionService` | Calling app | Normally the provider | `RecognitionListener` callbacks |
| `SpeechRecognizer` with `RecognizerIntent.EXTRA_AUDIO_SOURCE` | Calling app | Calling app | `RecognitionListener` callbacks |
| Voice IME | Offline Speech to Text keyboard | Offline Speech to Text | Text committed through Android's input-method contract |

Use the provider activity when compatibility matters more than a custom interface. Use `SpeechRecognizer` only after resolving a service that is eligible on the current device. Use caller-supplied audio for app-owned controls on Android 13+ only after establishing that the exact provider build consumes it. Android enables and selects the Offline Speech to Text IME through user-mediated system UI.

All `SpeechRecognizer` construction and control calls must run on the main thread.

The experimental **Prepare long dictation while recording** setting affects only
OSTT's own voice keyboard. It prepares
existing overlapping windows while keeping results private and retaining continuous
speech across ordinary pauses. Detailed troubleshooting is a separate, warned local opt-in for one hour, 24 hours or until manually disabled;
see [Power and performance diagnostics](power-diagnostics.md). It preserves
provider eligibility and the caller's ownership and retry obligations.

## Provider-owned recognition UI

Launch the standard intent with the Activity Result API:

```kotlin
private val recognize = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
) { result ->
    val text = result.data
        ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        ?.firstOrNull()
    if (result.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) {
        acceptTranscript(text)
    }
}

fun openVoicePanel() {
    recognize.launch(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1),
    )
}
```

The resolved provider activity owns permission prompts, recording, stopping, and cancellation. Treat a missing handler as an ordinary outcome. Cancelled or empty results are also ordinary outcomes. Stopping another app's provider activity requires its own controls; retain one stable request owner until the result returns.

## App UI with provider microphone capture

Discover recognition services dynamically. Android 11+ callers that query services need package visibility:

```xml
<queries>
    <intent>
        <action android:name="android.speech.RecognitionService" />
    </intent>
</queries>
```

Resolve `Intent(RecognitionService.SERVICE_INTERFACE)` with `PackageManager.queryIntentServices`, reject disabled, unexported, or permission-mismatched entries, and choose only an explicit provider selected by the user or one unambiguous eligible entry. Pin the resulting `ComponentName` for the entire session:

```kotlin
val recognizer = SpeechRecognizer.createSpeechRecognizer(context, component)
recognizer.setRecognitionListener(listener)
recognizer.startListening(
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1),
)
```

Grant `RECORD_AUDIO` to the component that opens the microphone. Android may prevent an unselected, non-preinstalled recognition service from inheriting the caller's while-in-use microphone capability; that commonly ends as `SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS`. Choose a supported capture path before retrying. Either use verified caller-supplied audio or fall back to the provider activity before recording.

## Custom UI with caller-supplied audio

`RecognizerIntent.EXTRA_AUDIO_SOURCE` is public from Android 13 (API 33). The caller places a `ParcelFileDescriptor` read end in the recognition intent and streams raw audio into the write end. Offline Speech to Text currently accepts the format extras and is exercised with:

- 16,000 Hz sample rate
- one channel
- signed PCM 16-bit little-endian samples
- an unsegmented session, so the final transcript arrives through `onResults`

The caller owns the microphone and endpoint. Closing the pipe or calling `stopListening()` ends the utterance; supplied audio uses caller-owned endpointing. Android 12+ attribution remains caller-owned: Offline Speech to Text creates a caller-attributed context from `RecognitionService.Callback.getCallingAttributionSource()` before consuming the descriptor.

### Preflight and fallback

Establish caller-audio support through a runtime check before the first real capture:

1. Resolve and pin one eligible provider component.
2. Require API 33+ and the caller's microphone permission/AppOps access.
3. Cache capability by provider component and installed version, never by package alone.
4. For an unknown build, run a bounded probe with `EXTRA_AUDIO_SOURCE` pointing to a pipe whose write end is already closed. The probe uses an empty descriptor with the microphone closed.
5. Treat a final result, `ERROR_NO_MATCH`, or `ERROR_SPEECH_TIMEOUT` as evidence that the provider read the descriptor. `ERROR_INSUFFICIENT_PERMISSIONS` or `ERROR_CLIENT` means this route is unsupported; other errors and timeout leave support unknown.
6. Re-resolve the provider after the probe. Discard the answer if the component or version changed.
7. Choose caller audio or provider activity before starting `AudioRecord`. Keep the microphone closed until this decision is complete.

Offline Speech to Text currently reports ready, beginning-of-speech, RMS, end-of-speech, final-result, and error callbacks. Results arrive through the final-result callback. Set `EXTRA_PARTIAL_RESULTS` to `false` and design the UI around a final transcript.

Offline Speech to Text snapshots the private preferred-word list once per transcription. Capable Whisper backends receive a bounded prompt; every model also restores exact preferred words and phrases, including hyphenated and apostrophe-joined names with identical punctuation, applies conservative close-spelling cleanup to single words, and recognizes a few narrowly defined spoken identifier forms after decoding. For known-English output, a standalone “uh” filler is removed before dictionary processing; unknown or non-English output is unchanged. The multilingual Parakeet v3 model does not reliably report a detected language in Auto mode, so users should select English in OSTT's model-language setting to enable this cleanup. If the user enables advanced exact correction pairs, they then run on the final assembled transcript so an explicit rule wins over a preferred match or close-spelling guess. Every activity, recognition-service, file, subtitle, long-recording, and IME result receives the same final processing. Whisper hints and cleanup derive from one immutable word-list read per complete job. Exact replacements stay protected when preferred cleanup runs; each pass also checks the whole match against quotes, code, URLs, email addresses, and dotted identifiers. OSTT supplies the complete cleanup pass for integrators. This is not native Parakeet phrase boosting or general phonetic matching, and dictionary contents are never exposed to callers or diagnostic logs.

Long caller-audio sessions remain one ordinary recognition request. OSTT keeps the supplied PCM as one continuous utterance and calls the standard final `onResults` only after the writer closes or the caller stops listening. Ordinary dictations use one model run. Whisper owns its native long-input windows; long input to other offline families is bounded with overlapping quiet-point windows and text-overlap reconciliation inside OSTT. An ordinary hesitation is never used as an app-level endpoint. Do not enable `EXTRA_SEGMENTED_SESSION` or concatenate partial callbacks. `cancel()` discards the captured utterance.

### Caller-audio completion and errors

Closing the writer after its final PCM frame is the clearest completion signal. On OSTT
0.12.2+, `stopListening()` also drains PCM already readable by the service before
finalizing. An unclosed idle writer does not hold Stop open. Total drain time after Stop
is bounded to 500 ms; exceeding that limit returns `ERROR_AUDIO`. Cancellation discards the request. Descriptor read/poll errors and incomplete
PCM frames return `ERROR_AUDIO` and discard the truncated recording.
Finish writing each whole PCM frame before requesting Stop (one frame is
`channel_count × bytes_per_sample` bytes). Stop does not wait for a future writer to
complete a partial frame; that request returns `ERROR_AUDIO`. Close the writer
only after its final complete frame when using EOF instead.

`ERROR_NO_MATCH` means no recognized text, not proof that the supplied audio was silent.
OSTT never turns an empty decode into a successful empty result. Clients should preserve
previously recognized chunks and bound retries. Let the user choose what to do with an
unrecognized remainder. Opt-in content-free diagnostics distinguish insufficient audio,
empty decoding, input failure and recognized text using fixed outcome labels and audio
duration; they never include audio or transcript content.

### Scoped Kotlin session

The following core is intentionally scoped to an API 33+ activity that is visible, already has `RECORD_AUDIO`, already resolved an eligible component, and already passed the capability preflight above. It uses only public Android APIs. The caller supplies `isOriginCurrent` so a late result cannot enter a different account, conversation, editor, or draft revision.

A production app must also provide permission UI, provider/version cache invalidation, bounded start/result timeouts, configuration/process restoration, audio-focus policy, and user-facing error mapping.

```kotlin
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.max

class CallerAudioSpeechSession(
    context: Context,
    component: ComponentName,
    private val isOriginCurrent: () -> Boolean,
    private val onState: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFailure: (Int) -> Unit,
) : RecognitionListener {
    private enum class SessionState {
        NEW, STREAMING, WAITING_RESULT, FAILURE_PENDING, COMPLETED, CANCELLED, DESTROYED,
    }

    private val main = Handler(Looper.getMainLooper())
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context, component)
    private val state = AtomicReference(SessionState.NEW)
    private val captureLock = Any()
    private var recorder: AudioRecord? = null
    private var providerEnd: ParcelFileDescriptor? = null
    private var writeEnd: ParcelFileDescriptor? = null

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(android.os.Build.VERSION.SDK_INT >= 33)
        recognizer.setRecognitionListener(this)
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(state.compareAndSet(SessionState.NEW, SessionState.STREAMING))

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            synchronized(captureLock) {
                providerEnd = pipe[0]
                writeEnd = pipe[1]
            }
            makeNonBlocking(pipe[1])

            val minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(0)
            val openedRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBytes, FRAMES_PER_READ * BYTES_PER_SAMPLE * 4),
            )
            synchronized(captureLock) { recorder = openedRecorder }
            check(openedRecorder.state == AudioRecord.STATE_INITIALIZED)
            openedRecorder.startRecording()
            check(openedRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

            recognizer.startListening(intent)
            thread(name = "caller-audio", isDaemon = true) { streamPcm16(openedRecorder, pipe[1]) }
        } catch (error: Throwable) {
            state.set(SessionState.CANCELLED)
            releaseCapture()
            runCatching { recognizer.cancel() }
            throw error
        }
    }

    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!state.compareAndSet(SessionState.STREAMING, SessionState.WAITING_RESULT)) return
        releaseCapture()
        main.post {
            if (state.get() == SessionState.WAITING_RESULT) {
                runCatching { recognizer.stopListening() }
                    .onFailure { reportFailure(SpeechRecognizer.ERROR_CLIENT) }
            }
        }
    }

    fun cancel() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val previous = state.getAndSet(SessionState.CANCELLED)
        if (previous == SessionState.DESTROYED || previous == SessionState.CANCELLED) return
        releaseCapture()
        recognizer.cancel()
    }

    fun destroy() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (state.getAndSet(SessionState.DESTROYED) == SessionState.DESTROYED) return
        releaseCapture()
        runCatching { recognizer.cancel() }
        runCatching { recognizer.destroy() }
    }

    private fun releaseCapture() {
        val resources = synchronized(captureLock) {
            Triple(recorder, writeEnd, providerEnd).also {
                recorder = null
                writeEnd = null
                providerEnd = null
            }
        }
        runCatching { resources.first?.stop() }
        runCatching { resources.first?.release() }
        runCatching { resources.second?.close() } // EOF ends caller audio.
        runCatching { resources.third?.close() }
    }

    private fun streamPcm16(input: AudioRecord, output: ParcelFileDescriptor) {
        val samples = ShortArray(FRAMES_PER_READ)
        val bytes = ByteArray(FRAMES_PER_READ * BYTES_PER_SAMPLE)
        try {
            while (state.get() == SessionState.STREAMING) {
                val count = input.read(samples, 0, samples.size)
                if (count <= 0) break
                for (i in 0 until count) {
                    val value = samples[i].toInt()
                    bytes[i * 2] = (value and 0xff).toByte()
                    bytes[i * 2 + 1] = ((value ushr 8) and 0xff).toByte()
                }
                writeWithoutBlocking(output, bytes, count * BYTES_PER_SAMPLE)
            }
        } finally {
            failCaptureFromWorker(SpeechRecognizer.ERROR_AUDIO)
            releaseCapture()
        }
    }

    private fun writeWithoutBlocking(
        output: ParcelFileDescriptor,
        bytes: ByteArray,
        length: Int,
    ) {
        var offset = 0
        while (offset < length && state.get() == SessionState.STREAMING) {
            try {
                val written = Os.write(output.fileDescriptor, bytes, offset, length - offset)
                if (written <= 0) return
                offset += written
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EAGAIN) return // Drop; never strand the mic.
                failCaptureFromWorker(SpeechRecognizer.ERROR_AUDIO)
                return
            }
        }
    }

    private fun reportFailure(error: Int) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!completeActive()) return
        releaseCapture()
        onFailure(error)
    }

    private fun failCaptureFromWorker(error: Int) {
        if (!state.compareAndSet(SessionState.STREAMING, SessionState.FAILURE_PENDING)) return
        releaseCapture()
        main.post {
            if (state.compareAndSet(SessionState.FAILURE_PENDING, SessionState.COMPLETED)) {
                onFailure(error)
            }
        }
    }

    private fun completeActive(): Boolean {
        while (true) {
            val current = state.get()
            if (current != SessionState.STREAMING && current != SessionState.WAITING_RESULT) {
                return false
            }
            if (state.compareAndSet(current, SessionState.COMPLETED)) return true
        }
    }

    private fun makeNonBlocking(descriptor: ParcelFileDescriptor) {
        val flags = Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_GETFL, 0)
        Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
    }

    override fun onReadyForSpeech(params: Bundle?) = onState("ready")
    override fun onBeginningOfSpeech() = onState("speech")
    override fun onRmsChanged(rmsdB: Float) = onState("level:$rmsdB")
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = onState("processing")

    override fun onResults(results: Bundle?) {
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
        val shouldAccept = completeActive()
        releaseCapture()
        if (shouldAccept && isOriginCurrent() && !text.isNullOrBlank()) {
            onFinal(text)
        }
    }

    override fun onError(error: Int) {
        reportFailure(error)
    }
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAMES_PER_READ = 1_600 // 100 ms
        const val BYTES_PER_SAMPLE = 2
    }
}
```

`start()` either starts the complete pipe/recorder/recognizer session or discards every resource it opened before rethrowing. Terminal paths share an idempotent `releaseCapture()`: Stop closes audio and requests one final result, while Cancel, Error, Destroy, and capture failure close audio and invalidate the result. Atomic state transitions make a worker-thread failure win before its callback is queued, and make queued Stop, Error, and Result work a no-op after another terminal path wins.

The platform-neutral [caller-audio lifecycle core](examples/CallerAudioSessionCore.java) isolates that state machine behind fakeable recorder, descriptor, recognizer, and main-queue interfaces. `tests/CallerAudioLifecycleTest.java` executes synchronous start failure, asynchronous read/write failure, Stop, Cancel, Results, Error, Destroy, and queued-callback races; keep it aligned when adapting the Android wrapper.

A blocked provider reader can fill a pipe. The capture side must therefore be nonblocking or use a bounded queue with a drop/fail policy; an unbounded blocking write can leave the microphone open after Stop or Cancel. Do not log PCM, transcript text, target identifiers, message drafts, or content-derived filenames. Content-free state, duration, byte counts, dropped-byte counts, and error codes are sufficient for diagnosis.

## Lifecycle and result safety

Treat one recognition attempt as one generation with one immutable destination snapshot.

- `onReadyForSpeech`: show ready/listening state.
- `onBeginningOfSpeech`: show that speech was detected.
- `onRmsChanged`: update a level meter; do not infer or store content.
- `onEndOfSpeech`: stop showing capture but keep the destination reserved while inference finishes.
- `onResults`: accept only the first nonblank final result after exact-origin validation.
- `onError`: release capture and present a recoverable, content-free error.
- `stopListening`: close caller audio and request a final result.
- `cancel`: close caller audio and discard the generation; ignore late callbacks.
- `destroy`: call on the main thread when the owning lifecycle ends.

Before inserting text, revalidate every origin dimension that matters: session generation, account, conversation or document, editor identity, draft revision, and whether the field has become sensitive. Atomically stage each non-cancelled final result in app-private no-backup storage before attempting delivery. Clear it only after the destination confirms insertion or the user explicitly copies or deletes it. A result for a stale origin must be presented in an explicit recovery UI; it must never be inserted into whichever field happens to be active or silently discarded. Keep result content out of logs, notifications, Intent extras, saved-instance state, and analytics.

If capture can continue after the activity is no longer visible, the caller—not the recognition provider—must run a microphone-typed foreground service. Declare `android:foregroundServiceType="microphone"` and `FOREGROUND_SERVICE`; Android 14+ also requires `FOREGROUND_SERVICE_MICROPHONE`. Start it while the app still has while-in-use microphone eligibility. Show an honest ongoing notification throughout capture, then stop the service as soon as capture closes. Handle notification permission and start restrictions explicitly. Never hide active capture or continue it after process/lifecycle recovery without fresh user intent.

## Long recording lifecycle

Long recording is an explicit app-owned foreground-service flow for speech that must continue after you leave the launch screen. Open **Long recording** from the app or voice IME, wait for the launch activity to be visibly resumed, then make the second user gesture with **Start recording**. Recording does not start if microphone permission or notification permission is denied, notifications are disabled, the Active recording channel is blocked, or no usable model is selected.

The private foreground notification is the control surface:

- **Preparing recording** / **Microphone off • cancel to stop setup** exposes only **Cancel start**.
- **Recording speech** / **Microphone on • tap an action to finish** exposes **Stop and discard** and **Stop and transcribe**.
- `Transcribing — microphone off` exposes only **Discard** and reads **Processing on device**. Audio focus is abandoned before inference starts.

A successful transcript opens a private review surface. The review surface holds the transcript in process memory, with insertion into editors disabled. Exposure outside that memory requires **Share and clear result**. **Delete result** and the notification's Delete action both discard it. Expiry also discards it. A pending result remains only in process memory for up to ten minutes; it does not survive process death, force-stop, reboot, or TTL expiry. Starting another recording while a result is pending opens the retained result.

Android shows its normal microphone privacy indicator during capture. Microphone privacy, capture errors, audio focus loss, denied permissions, a missing notification, or a platform foreground-service timeout fail closed: capture stops and buffered audio is discarded; result publication is suppressed. Removing the app from Recents does not stop an independently visible recording; Android Task Manager stop, force-stop, and process or device lifecycle recovery do stop it and never restart capture without a fresh visible activity and second user gesture.

## Voice IME contract

Offline Speech to Text also declares an `InputMethodService` with a voice subtype. Users enable it in Android keyboard settings and select it with the system keyboard switcher. HeliBoard and several other keyboards route their microphone button directly to the configured voice IME.

The IME uses the current focused, non-sensitive `InputConnection` for final delivery because Compose and other reactive toolkits may recreate their editor lifecycle while keeping the same visible composer. If Android is between connections, OSTT briefly retries connection acquisition while preserving the destination. Every non-cancelled final transcript is first written atomically to one checksummed, app-private, no-backup recovery slot. Confirmed `commitText` clears it; if a writable safe composer remains unavailable or insertion is rejected, the keyboard exposes copy/keep/delete recovery within the keyboard. New IME recordings are blocked until that result is recovered, to preserve the pending result. Explicit **Cancel** remains the only path that intentionally discards the current recording.

Public Android APIs require user-mediated selection and activation of another app's IME. If your product needs controls inside its own UI, use one of the three recognition contracts above. If a keyboard switch is acceptable, direct the user to Android's standard input-method settings or picker.

## Optional interactive auto-stop

The voice keyboard and provider-owned popup share **Stop after silence**, off by default. When enabled, **Pause before stopping** offers two seconds (default), three seconds, or five seconds. Longer choices allow hesitation but delay the final result. The preference is snapshotted at the start of each recording; pause/resume and microphone-unblock handling retain their existing timing rules. The eight-second initial-silence timeout and ten-minute interactive safety cap are independent of this choice. Custom callers retain control over termination: close the pipe or call `stopListening()` to finish, and use `cancel()` to discard. Their audio is never ended by this preference.
