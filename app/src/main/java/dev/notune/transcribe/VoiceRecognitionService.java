package dev.notune.transcribe;

import android.content.ContextParams;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * Exposes the offline transcriber as a system speech-to-text provider via
 * {@link android.speech.RecognitionService}. This is the API that keyboards
 * (Microsoft SwiftKey, Gboard, …) use through {@link SpeechRecognizer} to find
 * and drive an on-device recognizer.
 *
 * <p>Because the service is declared in the manifest, it is discoverable at all
 * times — even when the app process is not running or has been force-stopped by
 * the OS — so {@code SpeechRecognizer.isRecognitionAvailable()} stays true and
 * keyboards no longer report that "Google Speech Services aren't installed".
 *
 * <p>The heavy lifting (capture, silence endpointing, model inference) happens in
 * native code ({@code src/recog_service.rs}); this class only bridges the
 * {@link RecognitionService.Callback} to it.
 */
public class VoiceRecognitionService extends RecognitionService {

    private static final String TAG = "OfflineVoiceInput";
    /** Rate the model runs at, and the assumed rate when a caller sends audio without saying. */
    private static final int SAMPLE_RATE_HZ = 16000;

    static {
        try {
            NativeLibraries.load();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Callback mCallback;
    private NativeCallbacks nativeCallbacks;
    private long callbackGeneration;
    private volatile TroubleshootingSession troubleshooting;
    /** The active {@link FileTranscriptionContract#EXTRA_AUDIO_URI} request, main thread only. */
    private FileRequest fileRequest;

    @Override
    public void onCreate() {
        super.onCreate();
        DiagnosticLog.event(getFilesDir(), "service_state",
                "state=created,version_code=" + BuildConfig.VERSION_CODE);
        try {
            initNative(this);
        } catch (Throwable t) {
            Log.e(TAG, "initNative failed", t);
        }
    }

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback callback) {
        // Android can deliver a replacement request before the dead caller's
        // cancellation reaches us (for example during a fast app/editor
        // switch). Retire native work first so the new request never receives
        // a false ERROR_AUDIO from the stale process-wide owner.
        ++callbackGeneration;
        mCallback = null;
        finishTroubleshooting("cancel");
        cancelFileRequest();
        try {
            cancelNative();
        } catch (Throwable t) {
            Log.e(TAG, "stale-session cleanup failed", t);
        }
        mCallback = callback;

        boolean fileRequested = hasExtra(recognizerIntent, FileTranscriptionContract.EXTRA_AUDIO_URI);
        ParcelFileDescriptor callerAudio = callerAudioSource(recognizerIntent);
        troubleshooting = TroubleshootingSession.start(this, "service",
                fileRequested ? "file" : callerAudio == null ? "microphone" : "caller_audio");
        nativeCallbacks = new NativeCallbacks(callback);
        if (fileRequested) {
            boolean ambiguous = callerAudio != null;
            if (callerAudio != null) {
                try { callerAudio.close(); } catch (java.io.IOException ignored) {}
            }
            startListeningFromFile(recognizerIntent, callback, ambiguous);
            return;
        }
        if (callerAudio != null) {
            startListeningFromCallerAudio(recognizerIntent, callback, callerAudio);
            return;
        }

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted — open the app to grant it");
            safeError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
            return;
        }

        try {
            startListening(nativeCallbacks);
        } catch (Throwable t) {
            Log.e(TAG, "startListening failed", t);
            safeError(SpeechRecognizer.ERROR_CLIENT);
        }
    }

    /**
     * Transcribes audio the caller captured itself, read from
     * {@link RecognizerIntent#EXTRA_AUDIO_SOURCE}, instead of opening the microphone here.
     *
     * <p>This is the only path by which an ordinary app can drive this recognizer on a device where
     * it is not the system-selected one. {@code SpeechRecognitionManagerServiceImpl.checkPrivilege}
     * grants {@code BIND_INCLUDE_CAPABILITIES} only to the component named in
     * {@code Settings.Secure.VOICE_RECOGNITION_SERVICE}, the configured on-device recognizer, or a
     * preinstalled one. Every other recognizer is bound with plain {@code BIND_AUTO_CREATE} and so
     * never holds the while-in-use microphone capability that a "only while using the app"
     * RECORD_AUDIO grant requires, which makes the RECORD_AUDIO proxy note in
     * {@code RecognitionService.dispatchStartListening} fail and the caller receive
     * {@code ERROR_INSUFFICIENT_PERMISSIONS}. Since Android 17 removed the recognizer picker from
     * Settings, most users cannot select this app at all, so that describes every third-party
     * caller. Taking the audio from the caller sidesteps it: the caller already holds the
     * microphone it is recording with, and this service opens no microphone of its own.
     *
     * <p>The caller owns the endpoint on this path: the utterance ends when it closes the
     * descriptor or calls {@code stopListening}, never on a pause. Trailing-silence endpointing is
     * a microphone-mode heuristic, and applying it to supplied audio would truncate any recording
     * that contains one.
     */
    private void startListeningFromCallerAudio(
            Intent recognizerIntent, Callback callback, ParcelFileDescriptor callerAudio) {
        attributeSessionToCaller(callback);

        int fd;
        try {
            fd = callerAudio.detachFd();
        } catch (Throwable t) {
            Log.e(TAG, "audio source descriptor unusable", t);
            safeError(SpeechRecognizer.ERROR_AUDIO);
            return;
        }

        try {
            startListeningFromAudioSource(
                    nativeCallbacks,
                    fd,
                    recognizerIntent.getIntExtra(
                            RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE_HZ),
                    recognizerIntent.getIntExtra(
                            RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1),
                    recognizerIntent.getIntExtra(
                            RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT));
        } catch (Throwable t) {
            Log.e(TAG, "startListeningFromAudioSource failed", t);
            safeError(SpeechRecognizer.ERROR_CLIENT);
        }
    }

    /**
     * Transcribes an existing audio file named by {@link FileTranscriptionContract#EXTRA_AUDIO_URI}.
     *
     * <p>The caller grants read access to a {@code content:} Uri before {@code startListening};
     * nothing here opens a microphone or launches an activity, so a caller running as a
     * foreground service with the screen locked is served the same way. The session is
     * attributed to the caller for the same reason as caller-supplied audio: the platform's
     * RECORD_AUDIO data-delivery note guards a microphone this path never opens.
     *
     * <p>Decoding and inference use the bounded file pipeline shared with the share screen,
     * with no interactive timeout, silence stop or session cap. The final transcript arrives
     * through {@code onResults}; when {@link FileTranscriptionContract#EXTRA_OUTPUT_URI} is
     * present it is first written there as UTF-8, and a failed write fails the request.
     * {@code stopListening} does not end a file request early; {@code cancel} discards it.
     */
    private void startListeningFromFile(Intent intent, Callback callback, boolean hasCallerAudio) {
        attributeSessionToCaller(callback);

        Uri audio = uriExtra(intent, FileTranscriptionContract.EXTRA_AUDIO_URI);
        boolean outputRequested = hasExtra(intent, FileTranscriptionContract.EXTRA_OUTPUT_URI);
        Uri output = outputRequested ? uriExtra(intent, FileTranscriptionContract.EXTRA_OUTPUT_URI) : null;
        boolean callerIsSelf = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && callback.getCallingUid() == Process.myUid();
        if (!FileTranscriptionContract.acceptRequest(
                FileTranscriptionAccess.scheme(audio), FileTranscriptionAccess.isOwnProvider(this, audio),
                FileTranscriptionAccess.scheme(output), FileTranscriptionAccess.isOwnProvider(this, output),
                outputRequested && output == null, hasCallerAudio, callerIsSelf)) {
            safeError(SpeechRecognizer.ERROR_CLIENT);
            return;
        }
        if (!SpeechModelInventory.hasUsableModel(getFilesDir(), BuildConfig.HAS_BUNDLED_MODEL)) {
            safeError(FileTranscriptionContract.recognizerError(FileTranscriptionContract.Failure.NO_MODEL));
            return;
        }

        FileRequest request = new FileRequest(nativeCallbacks);
        fileRequest = request;
        // Provider calls may block on another process: keep them off the main thread.
        Thread opener = new Thread(() -> request.open(audio, output), "transcribe-file-open");
        opener.start();
    }

    private void cancelFileRequest() {
        FileRequest request = fileRequest;
        fileRequest = null;
        if (request != null) request.cancel();
    }

    private static boolean hasExtra(Intent intent, String name) {
        try {
            return intent != null && intent.hasExtra(name);
        } catch (Throwable t) {
            // Unparcelable extras from another process make the whole request malformed.
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static Uri uriExtra(Intent intent, String name) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return intent.getParcelableExtra(name, Uri.class);
            }
            Object value = intent.getParcelableExtra(name);
            return value instanceof Uri ? (Uri) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** One file request. Every terminal path goes through the request's exact callbacks. */
    private final class FileRequest implements FileTranscription.Listener {
        private final NativeCallbacks target;
        private volatile boolean cancelled;
        private FileTranscription job;
        private ParcelFileDescriptor output;

        FileRequest(NativeCallbacks target) {
            this.target = target;
        }

        /** Worker thread: separate a missing grant from undecodable audio, then start. */
        void open(Uri audio, Uri outputUri) {
            if (!FileTranscriptionAccess.canRead(VoiceRecognitionService.this, audio)) {
                finishWithFailure(FileTranscriptionContract.Failure.CLIENT);
                return;
            }
            ParcelFileDescriptor opened = null;
            if (outputUri != null) {
                // Open before decoding so an unwritable destination fails in milliseconds,
                // not after a long file has been transcribed. It stays empty until success.
                opened = FileTranscriptionAccess.openForWrite(VoiceRecognitionService.this, outputUri);
                if (opened == null) {
                    finishWithFailure(FileTranscriptionContract.Failure.CLIENT);
                    return;
                }
            }
            FileTranscription created = new FileTranscription(
                    VoiceRecognitionService.this, audio, false, this);
            synchronized (this) {
                if (cancelled) {
                    closeQuietly(opened);
                    return;
                }
                output = opened;
                job = created;
            }
            created.start();
        }

        void cancel() {
            FileTranscription running;
            ParcelFileDescriptor pending;
            synchronized (this) {
                cancelled = true;
                running = job;
                pending = output;
                job = null;
                output = null;
            }
            if (running != null) running.cancel();
            closeQuietly(pending);
        }

        private synchronized ParcelFileDescriptor takeOutput() {
            ParcelFileDescriptor taken = output;
            output = null;
            return taken;
        }

        private void finishWithFailure(FileTranscriptionContract.Failure failure) {
            closeQuietly(takeOutput());
            if (cancelled) return;
            int outcome = failure == FileTranscriptionContract.Failure.NO_SPEECH ? 2 : 3;
            if (failure != FileTranscriptionContract.Failure.NO_MODEL
                    && failure != FileTranscriptionContract.Failure.ENGINE) {
                target.onRecognitionOutcome(outcome, 0);
            }
            target.onError(FileTranscriptionContract.recognizerError(failure));
        }

        @Override public void onStatus(String status) {
            target.onStatusUpdate(status);
        }
        @Override public void onReady() {
            if (!cancelled) target.onReadyForSpeech();
        }
        @Override public void onPartial(String text) { }
        @Override public void onProgress(long decodedSamples, long durationUs) { }
        @Override public void onDecodeComplete() {
            if (!cancelled) target.onEndOfSpeech();
        }
        @Override public void onResult(String text, long audioMs, long computeMs) {
            ParcelFileDescriptor destination = takeOutput();
            if (cancelled) {
                closeQuietly(destination);
                return;
            }
            if (destination != null && !FileTranscriptionAccess.write(destination, text)) {
                finishWithFailure(FileTranscriptionContract.Failure.CLIENT);
                return;
            }
            if (cancelled) return;
            target.onTranscriptionMetrics(audioMs, computeMs, 0);
            target.onRecognitionOutcome(0, audioMs);
            target.onResults(text);
        }
        @Override public void onFailure(FileTranscriptionContract.Failure failure) {
            finishWithFailure(failure);
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try { descriptor.close(); } catch (java.io.IOException ignored) {}
    }

    @SuppressWarnings("deprecation")
    private ParcelFileDescriptor callerAudioSource(Intent recognizerIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return recognizerIntent.getParcelableExtra(
                        RecognizerIntent.EXTRA_AUDIO_SOURCE, ParcelFileDescriptor.class);
            }
            return recognizerIntent.getParcelableExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE);
        } catch (Throwable t) {
            Log.w(TAG, "audio source extra unreadable", t);
            return null;
        }
    }

    /**
     * Declares that this session's sensitive data is attributed to the caller.
     *
     * <p>{@code RecognitionService} treats the creation of a caller attribution context as the
     * service taking over blame, and skips its own RECORD_AUDIO proxy note for the session. That
     * note is what fails for a recognizer the platform has not selected, and on this path there is
     * nothing left for it to guard: the caller opened the microphone, and the audio arrives as a
     * descriptor it already owns.
     */
    private void attributeSessionToCaller(Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            createContext(
                    new ContextParams.Builder()
                            .setNextAttributionSource(callback.getCallingAttributionSource())
                            .build());
        } catch (Throwable t) {
            Log.w(TAG, "caller attribution refused", t);
        }
    }

    @Override
    protected void onStopListening(Callback callback) {
        if (callback != mCallback) return;
        // A file request has no capture to stop; it ends with its result or on cancel().
        if (fileRequest != null) return;
        if (troubleshooting != null) troubleshooting.event("processing", "stop");
        try {
            stopListening();
        } catch (Throwable t) {
            Log.e(TAG, "stopListening failed", t);
        }
    }

    @Override
    protected void onCancel(Callback callback) {
        if (callback != mCallback) return;
        ++callbackGeneration;
        mCallback = null;
        finishTroubleshooting("cancel");
        cancelFileRequest();
        try {
            cancelNative();
        } catch (Throwable t) {
            Log.e(TAG, "cancel failed", t);
        }
    }

    @Override
    public void onDestroy() {
        mCallback = null;
        finishTroubleshooting("destroy");
        cancelFileRequest();
        try {
            destroyNative();
        } catch (Throwable t) {
            Log.e(TAG, "destroyNative failed", t);
        }
        super.onDestroy();
    }

    // --- Callbacks invoked from native code (any thread) ---------------------

    public void onReadyForSpeech() {
        Callback cb = mCallback;
        long generation = callbackGeneration;
        if (cb == null) return;
        mainHandler.post(() -> {
            if (mCallback != cb || callbackGeneration != generation) return;
            try { cb.readyForSpeech(new Bundle()); } catch (RemoteException ignored) {}
        });
    }

    public void onBeginningOfSpeech() {
        Callback cb = mCallback;
        long generation = callbackGeneration;
        if (cb == null) return;
        mainHandler.post(() -> {
            if (mCallback != cb || callbackGeneration != generation) return;
            try { cb.beginningOfSpeech(); } catch (RemoteException ignored) {}
        });
    }

    public void onRmsChanged(float rmsdB) {
        Callback cb = mCallback;
        long generation = callbackGeneration;
        if (cb == null) return;
        mainHandler.post(() -> {
            if (mCallback != cb || callbackGeneration != generation) return;
            try { cb.rmsChanged(rmsdB); } catch (RemoteException ignored) {}
        });
    }

    public void onEndOfSpeech() {
        Callback cb = mCallback;
        long generation = callbackGeneration;
        if (cb == null) return;
        mainHandler.post(() -> {
            if (mCallback != cb || callbackGeneration != generation) return;
            if (troubleshooting != null) troubleshooting.event("processing", "end");
            try { cb.endOfSpeech(); } catch (RemoteException ignored) {}
        });
    }

    public void onResults(String text) {
        Callback cb = mCallback;
        long generation = callbackGeneration;
        if (cb == null) return;
        mainHandler.post(() -> {
            if (mCallback != cb || callbackGeneration != generation) return;
            ArrayList<String> hypotheses = new ArrayList<>();
            hypotheses.add(text);
            Bundle bundle = new Bundle();
            bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, hypotheses);
            bundle.putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, new float[] { -1.0f });
            boolean delivered = false;
            try { cb.results(bundle); delivered = true; } catch (RemoteException ignored) {}
            String reason = delivered ? "delivered" : "callback_failed";
            DiagnosticLog.event(getFilesDir(), "callback_delivery", "process=service,reason=" + reason);
            finishTroubleshooting(reason);
            if (mCallback == cb) mCallback = null;
        });
    }

    public void onError(int errorCode) {
        Callback cb = mCallback;
        long generation = callbackGeneration;
        if (cb == null) return;
        DiagnosticLog.recognizerError(getFilesDir(), errorCode);
        mainHandler.post(() -> {
            if (mCallback != cb || callbackGeneration != generation) return;
            try { cb.error(errorCode); } catch (RemoteException ignored) {}
            finishTroubleshooting("error");
            if (mCallback == cb) mCallback = null;
        });
    }

    /** Invoked by the shared engine loader during model warm-up; UI-less here. */
    public void onStatusUpdate(String status) {
        DiagnosticLog.event(getFilesDir(), "service_state",
                "state=" + NativeStatus.diagnosticLabel(status));
    }

    /** Content-free timing emitted only when the user opted into diagnostics. */
    public void onTranscriptionMetrics(long audioMs, long computeMs, int threads) {
        DiagnosticLog.event(getFilesDir(), "timing",
                "process=service,audio_ms=" + Math.max(0L, audioMs)
                        + ",duration_ms=" + Math.max(0L, computeMs)
                        + ",cpu_threads=" + Math.max(0, threads));
    }

    public long getTroubleshootingSessionId() {
        TroubleshootingSession session = troubleshooting;
        return session == null ? 0L : session.id();
    }

    public void onWindowPlan(long sessionId, long rawLimitMs, int plannedWindows) {
        TroubleshootingSession session = troubleshooting;
        if (session != null && session.id() == sessionId) session.windowPlan(rawLimitMs, plannedWindows);
    }

    public void onPipelineMetrics(long sessionId, long prepareMs, long decodeMs, long postprocessMs, long stopWaitMs, int windows, int family, int warm) {
        TroubleshootingSession session = troubleshooting;
        if (session != null && session.id() == sessionId) session.pipeline(prepareMs, decodeMs, postprocessMs, stopWaitMs, windows, family, warm);
    }

    private void finishTroubleshooting(String reason) {
        TroubleshootingSession session = troubleshooting;
        troubleshooting = null;
        if (session != null) session.finish(reason);
    }

    /** Fixed numeric native outcome; never implies that empty recognition was silent audio. */
    public void onRecognitionOutcome(int reason, long audioMs) {
        if (mCallback == null) return;
        DiagnosticLog.recognitionOutcome(getFilesDir(), reason, audioMs);
        if (troubleshooting != null) {
            String[] reasons = {"text", "insufficient_audio", "empty_decode", "input_error"};
            if (reason >= 0 && reason < reasons.length) troubleshooting.event("processing", reasons[reason]);
        }
    }

    private void safeError(int errorCode) {
        Callback cb = mCallback;
        long generation = callbackGeneration;
        if (cb == null) return;
        DiagnosticLog.recognizerError(getFilesDir(), errorCode);
        try { cb.error(errorCode); } catch (RemoteException ignored) {}
        finishTroubleshooting("error");
        mCallback = null;
    }

    /** JNI holds this exact request target, never a mutable current callback. */
    private final class NativeCallbacks extends android.content.ContextWrapper {
        private final Callback callback;
        private final long sessionId;
        private final long generation;
        NativeCallbacks(Callback callback) {
            super(VoiceRecognitionService.this);
            this.callback = callback;
            this.generation = callbackGeneration;
            this.sessionId = VoiceRecognitionService.this.getTroubleshootingSessionId();
        }
        private void deliver(Runnable action) {
            mainHandler.post(() -> { if (mCallback == callback && callbackGeneration == generation) action.run(); });
        }
        public void onReadyForSpeech() { deliver(VoiceRecognitionService.this::onReadyForSpeech); }
        public void onBeginningOfSpeech() { deliver(VoiceRecognitionService.this::onBeginningOfSpeech); }
        public void onEndOfSpeech() { deliver(VoiceRecognitionService.this::onEndOfSpeech); }
        public void onRmsChanged(float value) { deliver(() -> VoiceRecognitionService.this.onRmsChanged(value)); }
        public void onResults(String text) { deliver(() -> VoiceRecognitionService.this.onResults(text)); }
        public void onError(int code) { deliver(() -> VoiceRecognitionService.this.onError(code)); }
        public void onStatusUpdate(String status) { deliver(() -> VoiceRecognitionService.this.onStatusUpdate(status)); }
        public void onTranscriptionMetrics(long audio, long compute, int threads) { deliver(() -> VoiceRecognitionService.this.onTranscriptionMetrics(audio, compute, threads)); }
        public long getTroubleshootingSessionId() { return sessionId; }
        public void onRecognitionOutcome(int reason, long audio) { deliver(() -> VoiceRecognitionService.this.onRecognitionOutcome(reason, audio)); }
        public void onWindowPlan(long id, long rawLimitMs, int plannedWindows) {
            deliver(() -> VoiceRecognitionService.this.onWindowPlan(id, rawLimitMs, plannedWindows));
        }
        public void onPipelineMetrics(long id, long prepare, long decode, long post, long wait, int windows, int family, int warm) {
            deliver(() -> VoiceRecognitionService.this.onPipelineMetrics(id, prepare, decode, post, wait, windows, family, warm));
        }
    }

    // --- Native methods (implemented in src/recog_service.rs) ----------------

    private native void initNative(VoiceRecognitionService service);
    private native void startListening(android.content.Context service);
    private native void startListeningFromAudioSource(
            android.content.Context service,
            int fd,
            int sampleRate,
            int channelCount,
            int encoding);
    private native void stopListening();
    private native void cancelNative();
    private native void destroyNative();
}
