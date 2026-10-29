package dev.notune.transcribe;

import android.content.ContextParams;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.net.Uri;
import android.os.Process;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
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
 *
 * <p>Two independent request slots: one microphone request and one caller-audio
 * request ({@link RecognizerIntent#EXTRA_AUDIO_SOURCE}). Caller audio opens no
 * microphone and is transcribed as background work on the shared engine, so a
 * long one neither blocks nor is cancelled by a microphone request from another
 * app (or by OSTT's own keyboard). A second caller-audio request while one runs
 * receives {@link SpeechRecognizer#ERROR_RECOGNIZER_BUSY}.
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

    /** Concurrent sessions: one microphone request plus one caller-audio request. */
    private static final int MAX_SESSIONS = 2;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** One request slot. Fields are written on the main thread. */
    private static final class Slot {
        final String route;
        volatile Callback callback;
        long generation;
        volatile TroubleshootingSession troubleshooting;

        Slot(String route) {
            this.route = route;
        }
    }

    private final Slot mic = new Slot("microphone");
    private final Slot caller = new Slot("caller_audio");
    private final Slot file = new Slot("file");
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

    /**
     * Android 13+ admits at most this many sessions at once (the default is one);
     * the slots below decide what each may do.
     */
    @Override
    public int getMaxConcurrentSessionsCount() {
        return MAX_SESSIONS;
    }

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback callback) {
        try {
            if (recognizerIntent == null) throw new IllegalArgumentException();
            recognizerIntent.hasExtra(FileTranscriptionContract.EXTRA_AUDIO_URI);
            recognizerIntent.getExtras();
        } catch (RuntimeException malformed) {
            try { callback.error(SpeechRecognizer.ERROR_CLIENT); } catch (RemoteException ignored) {}
            return;
        }
        ParcelFileDescriptor callerAudio = callerAudioSource(recognizerIntent);
        if (hasExtra(recognizerIntent, RecognizerIntent.EXTRA_AUDIO_SOURCE) && callerAudio == null) {
            try { callback.error(SpeechRecognizer.ERROR_CLIENT); } catch (RemoteException ignored) {}
            return;
        }
        if (hasExtra(recognizerIntent, FileTranscriptionContract.EXTRA_AUDIO_URI)) {
            if (file.callback != null || caller.callback != null) {
                closeQuietly(callerAudio);
                try { callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); } catch (RemoteException ignored) {}
                return;
            }
            NativeCallbacks target = claim(file, callback);
            boolean ambiguous = callerAudio != null;
            closeQuietly(callerAudio);
            startListeningFromFile(recognizerIntent, callback, ambiguous, target);
            return;
        }
        Callback previous = mic.callback;
        RecognitionSessionPolicy.Action action = RecognitionSessionPolicy.admit(
                callerAudio != null, caller.callback != null || file.callback != null,
                previous != null && previous != callback);
        if (action == RecognitionSessionPolicy.Action.REFUSE_BUSY) {
            // One caller-audio job at a time; never cancel the running one.
            closeQuietly(callerAudio);
            Log.i(TAG, "service caller_audio: busy, refusing a second request");
            try { callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); } catch (RemoteException ignored) {}
            DiagnosticLog.recognizerError(getFilesDir(), SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
            return;
        }
        if (action == RecognitionSessionPolicy.Action.START_BACKGROUND) {
            NativeCallbacks target = claim(caller, callback);
            Log.i(TAG, "service caller_audio: start");
            startListeningFromCallerAudio(recognizerIntent, callback, callerAudio, target);
            return;
        }

        // Android can deliver a replacement request before the dead caller's
        // cancellation reaches us (for example during a fast app/editor
        // switch). Retire the previous microphone request first so the new one
        // never receives a false error from the stale process-wide owner, and
        // tell the replaced caller (busy, as a refused request is told) so it
        // never waits for a result that cannot come. A caller-audio job in the
        // other slot is left alone.
        retire(mic, "replaced");
        try {
            cancelNative();
        } catch (Throwable t) {
            Log.e(TAG, "stale-session cleanup failed", t);
        }
        if (action == RecognitionSessionPolicy.Action.REPLACE_MICROPHONE) {
            Log.i(TAG, "service microphone: replacing the previous request (busy sent to it)");
            DiagnosticLog.recognizerError(getFilesDir(), SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
            try { previous.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); } catch (RemoteException ignored) {}
        }
        NativeCallbacks target = claim(mic, callback);
        Log.i(TAG, "service microphone: start" + (caller.callback != null
                ? " (caller-audio job keeps running)" : ""));

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted — open the app to grant it");
            safeError(mic, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
            return;
        }

        try {
            startListening(target);
            mainHandler.postDelayed(() -> {
                if (mic.callback == callback && mic.generation == target.generation && !target.readyDelivered) {
                    try { cancelNative(); } catch (Throwable ignored) { }
                    safeError(mic, SpeechRecognizer.ERROR_AUDIO);
                }
            }, 15_000L);
        } catch (Throwable t) {
            Log.e(TAG, "startListening failed", t);
            safeError(mic, SpeechRecognizer.ERROR_CLIENT);
        }
    }

    /** Installs {@code callback} in {@code slot} and returns the exact native target for it. */
    private NativeCallbacks claim(Slot slot, Callback callback) {
        ++slot.generation;
        slot.callback = callback;
        slot.troubleshooting = TroubleshootingSession.start(this, "service", slot.route);
        return new NativeCallbacks(slot, callback);
    }

    /** Empties {@code slot}; late native callbacks for its old request are dropped. */
    private void retire(Slot slot, String reason) {
        ++slot.generation;
        slot.callback = null;
        finishTroubleshooting(slot, reason);
        if (slot == file) cancelFileRequest();
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try { descriptor.close(); } catch (java.io.IOException ignored) {}
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
    private void startListeningFromCallerAudio(Intent recognizerIntent, Callback callback,
            ParcelFileDescriptor callerAudio, NativeCallbacks target) {
        attributeSessionToCaller(callback);

        int fd;
        try {
            fd = callerAudio.detachFd();
        } catch (Throwable t) {
            Log.e(TAG, "audio source descriptor unusable", t);
            safeError(caller, SpeechRecognizer.ERROR_AUDIO);
            return;
        }

        try {
            startListeningFromAudioSource(
                    target,
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
            safeError(caller, SpeechRecognizer.ERROR_CLIENT);
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
    private void startListeningFromFile(Intent intent, Callback callback, boolean hasCallerAudio, NativeCallbacks target) {
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
            safeError(file, SpeechRecognizer.ERROR_CLIENT);
            return;
        }
        int uid = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? callback.getCallingUid() : -1;
        if (!FileTranscriptionAccess.callerCanAccess(this, audio, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                || (outputRequested && !FileTranscriptionAccess.callerCanAccess(
                        this, output, uid, Intent.FLAG_GRANT_WRITE_URI_PERMISSION))) {
            safeError(file, SpeechRecognizer.ERROR_CLIENT);
            return;
        }
        if (!SpeechModelInventory.hasUsableModel(getFilesDir(), BuildConfig.HAS_BUNDLED_MODEL)) {
            safeError(file, FileTranscriptionContract.recognizerError(FileTranscriptionContract.Failure.NO_MODEL));
            return;
        }

        FileRequest request = new FileRequest(target);
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
        if (callback == null) return;
        if (callback == file.callback) return;
        if (callback == caller.callback) {
            try {
                stopCallerAudio();
            } catch (Throwable t) {
                Log.e(TAG, "stopCallerAudio failed", t);
            }
            return;
        }
        if (callback != mic.callback) return;
        if (mic.troubleshooting != null) mic.troubleshooting.event("processing", "stop");
        try {
            stopListening();
        } catch (Throwable t) {
            Log.e(TAG, "stopListening failed", t);
        }
    }

    @Override
    protected void onCancel(Callback callback) {
        if (callback == null) return;
        if (callback == file.callback) {
            retire(file, "cancel");
            return;
        }
        if (callback == caller.callback) {
            Log.i(TAG, "service caller_audio: cancelled by the caller");
            retire(caller, "cancel");
            try {
                cancelCallerAudio();
            } catch (Throwable t) {
                Log.e(TAG, "cancelCallerAudio failed", t);
            }
            return;
        }
        if (callback != mic.callback) return;
        Log.i(TAG, "service microphone: cancelled by the caller");
        retire(mic, "cancel");
        try {
            cancelNative();
        } catch (Throwable t) {
            Log.e(TAG, "cancel failed", t);
        }
    }

    @Override
    public void onDestroy() {
        // A live request must not wait for a result that can no longer come.
        int gone = RecognitionSessionPolicy.destroyedError(Build.VERSION.SDK_INT);
        for (Slot slot : new Slot[] { mic, caller, file }) {
            Callback cb = slot.callback;
            retire(slot, "destroy");
            if (cb != null) {
                Log.i(TAG, "service " + slot.route + ": error " + gone + " (service destroyed)");
                try { cb.error(gone); } catch (RemoteException ignored) {}
            }
        }
        try {
            destroyNative();
        } catch (Throwable t) {
            Log.e(TAG, "destroyNative failed", t);
        }
        super.onDestroy();
    }

    // --- Callbacks invoked from native code, delivered on the main thread -----
    // Each is called through a request's NativeCallbacks, which has already
    // checked that the slot still belongs to that request.

    private void deliverReadyForSpeech(Callback cb) {
        try { cb.readyForSpeech(new Bundle()); } catch (RemoteException ignored) {}
    }

    private void deliverBeginningOfSpeech(Callback cb) {
        try { cb.beginningOfSpeech(); } catch (RemoteException ignored) {}
    }

    private void deliverRmsChanged(Callback cb, float rmsdB) {
        try { cb.rmsChanged(rmsdB); } catch (RemoteException ignored) {}
    }

    private void deliverEndOfSpeech(Slot slot, Callback cb) {
        if (slot.troubleshooting != null) slot.troubleshooting.event("processing", "end");
        try { cb.endOfSpeech(); } catch (RemoteException ignored) {}
    }

    private void deliverResults(Slot slot, Callback cb, String text) {
        ArrayList<String> hypotheses = new ArrayList<>();
        hypotheses.add(text);
        Bundle bundle = new Bundle();
        bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, hypotheses);
        bundle.putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, new float[] { -1.0f });
        boolean delivered = false;
        try { cb.results(bundle); delivered = true; } catch (RemoteException ignored) {}
        String reason = delivered ? "delivered" : "callback_failed";
        Log.i(TAG, "service " + slot.route + ": results " + reason);
        DiagnosticLog.event(getFilesDir(), "callback_delivery", "process=service,reason=" + reason);
        retire(slot, reason);
    }

    private void deliverError(Slot slot, Callback cb, int errorCode) {
        Log.i(TAG, "service " + slot.route + ": error " + errorCode);
        DiagnosticLog.recognizerError(getFilesDir(), errorCode);
        try { cb.error(errorCode); } catch (RemoteException ignored) {}
        retire(slot, "error");
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

    private static long troubleshootingId(Slot slot) {
        TroubleshootingSession session = slot.troubleshooting;
        return session == null ? 0L : session.id();
    }

    private void onWindowPlan(Slot slot, long sessionId, long rawLimitMs, int plannedWindows) {
        TroubleshootingSession session = slot.troubleshooting;
        if (session != null && session.id() == sessionId) session.windowPlan(rawLimitMs, plannedWindows);
    }

    private void onPipelineMetrics(Slot slot, long sessionId, long prepareMs, long decodeMs, long postprocessMs, long stopWaitMs, int windows, int family, int warm) {
        TroubleshootingSession session = slot.troubleshooting;
        if (session != null && session.id() == sessionId) session.pipeline(prepareMs, decodeMs, postprocessMs, stopWaitMs, windows, family, warm);
    }

    private static void finishTroubleshooting(Slot slot, String reason) {
        TroubleshootingSession session = slot.troubleshooting;
        slot.troubleshooting = null;
        if (session != null) session.finish(reason);
    }

    /** Fixed numeric native outcome; never implies that empty recognition was silent audio. */
    private void onRecognitionOutcome(Slot slot, int reason, long audioMs) {
        DiagnosticLog.recognitionOutcome(getFilesDir(), reason, audioMs);
        if (slot.troubleshooting != null) {
            String[] reasons = {"text", "insufficient_audio", "empty_decode", "input_error"};
            if (reason >= 0 && reason < reasons.length) slot.troubleshooting.event("processing", reasons[reason]);
        }
    }

    /** Synchronous error for a request that never reached native code. */
    private void safeError(Slot slot, int errorCode) {
        Callback cb = slot.callback;
        if (cb == null) return;
        deliverError(slot, cb, errorCode);
    }

    /** JNI holds this exact request target, never a mutable current callback. */
    private final class NativeCallbacks extends android.content.ContextWrapper {
        private final Slot slot;
        private final Callback callback;
        private final long sessionId;
        private final long generation;
        private boolean readyDelivered;
        NativeCallbacks(Slot slot, Callback callback) {
            super(VoiceRecognitionService.this);
            this.slot = slot;
            this.callback = callback;
            this.generation = slot.generation;
            this.sessionId = troubleshootingId(slot);
        }
        private void deliver(Runnable action) {
            mainHandler.post(() -> {
                if (slot.callback == callback && slot.generation == generation) action.run();
            });
        }
        public void onReadyForSpeech() { deliver(() -> { readyDelivered = true; deliverReadyForSpeech(callback); }); }
        public void onBeginningOfSpeech() { deliver(() -> deliverBeginningOfSpeech(callback)); }
        public void onEndOfSpeech() { deliver(() -> deliverEndOfSpeech(slot, callback)); }
        public void onRmsChanged(float value) { deliver(() -> deliverRmsChanged(callback, value)); }
        public void onResults(String text) { deliver(() -> deliverResults(slot, callback, text)); }
        public void onError(int code) { deliver(() -> deliverError(slot, callback, code)); }
        public void onStatusUpdate(String status) { deliver(() -> VoiceRecognitionService.this.onStatusUpdate(status)); }
        public void onTranscriptionMetrics(long audio, long compute, int threads) { deliver(() -> VoiceRecognitionService.this.onTranscriptionMetrics(audio, compute, threads)); }
        public long getTroubleshootingSessionId() { return sessionId; }
        public void onRecognitionOutcome(int reason, long audio) { deliver(() -> VoiceRecognitionService.this.onRecognitionOutcome(slot, reason, audio)); }
        public void onWindowPlan(long id, long rawLimitMs, int plannedWindows) {
            deliver(() -> VoiceRecognitionService.this.onWindowPlan(slot, id, rawLimitMs, plannedWindows));
        }
        public void onPipelineMetrics(long id, long prepare, long decode, long post, long wait, int windows, int family, int warm) {
            deliver(() -> VoiceRecognitionService.this.onPipelineMetrics(slot, id, prepare, decode, post, wait, windows, family, warm));
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
    /** Microphone request only. */
    private native void stopListening();
    /** Microphone request only. */
    private native void cancelNative();
    private native void stopCallerAudio();
    private native void cancelCallerAudio();
    private native void destroyNative();
}
