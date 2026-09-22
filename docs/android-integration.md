# Android integration

Offline Speech to Text exposes four Android contracts. Choose the contract before recording; they are not interchangeable.

## Choose a surface

| Contract | UI owner | Microphone owner | Result |
|---|---|---|---|
| `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` | Provider activity | Provider | Activity result in `RecognizerIntent.EXTRA_RESULTS` |
| `SpeechRecognizer` bound to an eligible `RecognitionService` | Calling app | Normally the provider | `RecognitionListener` callbacks |
| `SpeechRecognizer` with `RecognizerIntent.EXTRA_AUDIO_SOURCE` | Calling app | Calling app | `RecognitionListener` callbacks |
| Voice IME | Offline Speech to Text keyboard | Offline Speech to Text | Text committed through Android's input-method contract |

Use the provider activity when compatibility matters more than a custom interface. Use `SpeechRecognizer` only after resolving a service that is eligible on the current device. Use caller-supplied audio for app-owned controls on Android 13+ only after establishing that the exact provider build consumes it. A keyboard cannot remotely drive the Offline Speech to Text IME: Android enables and selects IMEs through user-mediated system UI.

All `SpeechRecognizer` construction and control calls must run on the main thread.

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

The resolved provider activity owns permission prompts, recording, stopping, and cancellation. Your activity must still treat a missing handler, a cancelled result, and an empty result as normal outcomes. It cannot synchronously stop another app's provider activity, so retain one stable request owner until the result returns.

## App UI with provider microphone capture

Discover recognition services rather than hard-coding a class name. Android 11+ callers that query services need package visibility:

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

Grant `RECORD_AUDIO` to the component that opens the microphone. Android may prevent an unselected, non-preinstalled recognition service from inheriting the caller's while-in-use microphone capability; that commonly ends as `SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS`. Do not retry in a loop. Either use verified caller-supplied audio or fall back to the provider activity before recording.

## Custom UI with caller-supplied audio

`RecognizerIntent.EXTRA_AUDIO_SOURCE` is public from Android 13 (API 33). The caller places a `ParcelFileDescriptor` read end in the recognition intent and streams raw audio into the write end. Offline Speech to Text currently accepts the format extras and is exercised with:

- 16,000 Hz sample rate
- one channel
- signed PCM 16-bit little-endian samples
- an unsegmented session, so the final transcript arrives through `onResults`

The caller owns the microphone and endpoint. Closing the pipe or calling `stopListening()` ends the utterance; pauses in supplied audio do not trigger the provider's microphone-mode trailing-silence endpoint. Android 12+ attribution remains caller-owned: Offline Speech to Text creates a caller-attributed context from `RecognitionService.Callback.getCallingAttributionSource()` before consuming the descriptor.

### Preflight and fallback

There is no platform capability flag for caller audio. Before the first real capture:

1. Resolve and pin one eligible provider component.
2. Require API 33+ and the caller's microphone permission/AppOps access.
3. Cache capability by provider component and installed version, never by package alone.
4. For an unknown build, run a bounded probe with `EXTRA_AUDIO_SOURCE` pointing to a pipe whose write end is already closed. The probe opens no microphone.
5. Treat a final result, `ERROR_NO_MATCH`, or `ERROR_SPEECH_TIMEOUT` as evidence that the provider read the descriptor. `ERROR_INSUFFICIENT_PERMISSIONS` or `ERROR_CLIENT` means this route is unsupported. Other errors and timeout are inconclusive.
6. Re-resolve the provider after the probe. Discard the answer if the component or version changed.
7. Choose caller audio or provider activity before starting `AudioRecord`. Never open the microphone and then launch the fallback activity.

Offline Speech to Text currently reports ready, beginning-of-speech, RMS, end-of-speech, final-result, and error callbacks. It does not emit partial-result callbacks. Set `EXTRA_PARTIAL_RESULTS` to `false` and design the UI around a final transcript.

When the user enables exact personal-dictionary corrections, Offline Speech to Text applies them once to the final assembled transcript before returning it through any activity, recognition-service, file, subtitle, long-recording, or IME surface. Integrators do not need a separate correction pass. Whisper recognition hints affect only capable Whisper backends; exact corrections are post-processing and therefore also work with Parakeet. Neither mode exposes dictionary contents to callers or diagnostic logs.

Long caller-audio sessions remain one ordinary recognition request. OSTT may decode completed, model-aware chunks while the pipe is still open, but it retains their source audio and ordering internally and calls the standard final `onResults` only after the writer closes or the caller stops listening. Do not enable `EXTRA_SEGMENTED_SESSION` or concatenate partial callbacks for this optimization. `cancel()` discards the staged work; a failed speculative chunk is retried from the retained complete recording before OSTT reports an error.

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

`start()` either starts the complete pipe/recorder/recognizer session or discards every resource it opened before rethrowing. The terminal paths share an idempotent `releaseCapture()`: Stop closes audio and requests one final result, while Cancel, Error, Destroy, and capture failure close audio and invalidate the result. The atomic state transitions make a worker-thread failure win before its callback is queued, and make queued Stop, Error, and Result work a no-op after another terminal path wins.

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
- `cancel`: close caller audio, discard the generation, and ignore late callbacks.
- `destroy`: call on the main thread when the owning lifecycle ends.

Before inserting text, revalidate every origin dimension that matters: session generation, account, conversation or document, editor identity, draft revision, and whether the field has become sensitive. Atomically stage each non-cancelled final result in app-private no-backup storage before attempting delivery. Clear it only after the destination confirms insertion or the user explicitly copies or deletes it. A result for a stale origin must be presented in an explicit recovery UI; it must never be inserted into whichever field happens to be active or silently discarded. Keep result content out of logs, notifications, Intent extras, saved-instance state, and analytics.

If capture can continue after the activity is no longer visible, the caller—not the recognition provider—must run a microphone-typed foreground service. Declare `android:foregroundServiceType="microphone"`, `FOREGROUND_SERVICE`, and on Android 14+ `FOREGROUND_SERVICE_MICROPHONE`; start it while the app still has while-in-use microphone eligibility, show an honest ongoing notification, and stop it as soon as capture closes. Handle notification permission and start restrictions explicitly. Never hide active capture or continue it after process/lifecycle recovery without fresh user intent.

## Long recording lifecycle

Long recording is an explicit app-owned foreground-service flow for speech that must continue after you leave the launch screen. Open **Long recording** from the app or voice IME, wait for the launch activity to be visibly resumed, then make the second user gesture with **Start recording**. Recording does not start if microphone permission or notification permission is denied, notifications are disabled, the Active recording channel is blocked, or no usable model is selected.

The private foreground notification is the control surface:

- **Preparing recording** / **Microphone off • cancel to stop setup** exposes only **Cancel start**.
- **Recording speech** / **Microphone on • tap an action to finish** exposes **Stop and discard** and **Stop and transcribe**.
- **Transcribing — microphone off** exposes only **Discard** and reads **Processing on device**. Audio focus is abandoned before inference starts.

A successful transcript opens a private review surface. It does not automatically insert text into the current or former editor and does not expose the transcript outside process memory until you press **Share and clear result**. **Delete result**, the notification's Delete action, or expiry discards it. A pending result remains only in process memory for up to ten minutes; it does not survive process death, force-stop, reboot, or TTL expiry. Starting another recording while a result is pending opens that result instead of replacing it.

Android shows its normal microphone privacy indicator during capture. Microphone privacy, capture errors, audio focus loss, denied permissions, a missing notification, or a platform foreground-service timeout fail closed: capture stops, buffered audio is discarded, and no result is published. Removing the app from Recents does not stop an independently visible recording; Android Task Manager stop, force-stop, and process or device lifecycle recovery do stop it and never restart capture without a fresh visible activity and second user gesture.

## Voice IME contract

Offline Speech to Text also declares an `InputMethodService` with a voice subtype. Users enable it in Android keyboard settings and select it with the system keyboard switcher. HeliBoard and several other keyboards route their microphone button to the configured voice IME rather than to `ACTION_RECOGNIZE_SPEECH`.

The IME uses the current focused, non-sensitive `InputConnection` for final delivery because Compose and other reactive toolkits may recreate their editor lifecycle while keeping the same visible composer. If Android is between connections, OSTT retries briefly instead of treating that recreation as a destination change. Every non-cancelled final transcript is first written atomically to one checksummed, app-private, no-backup recovery slot. Confirmed `commitText` clears it; if no writable safe composer becomes available or insertion is rejected, the keyboard exposes copy/keep/delete recovery without launching an OSTT screen over the current app. New IME recordings are blocked until that result is recovered, so it cannot be overwritten. Explicit **Cancel** remains the only path that intentionally discards the current recording.

An app cannot silently enable, select, start, or stop another app's IME through public Android APIs. If your product needs controls inside its own UI, use one of the three recognition contracts above. If a keyboard switch is acceptable, direct the user to Android's standard input-method settings or picker.
