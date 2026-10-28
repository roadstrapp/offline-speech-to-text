package dev.notune.transcribe;

import android.content.ContextParams;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
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
        ParcelFileDescriptor callerAudio = callerAudioSource(recognizerIntent);
        if (callerAudio != null) {
            if (caller.callback != null) {
                // One caller-audio job at a time; never cancel the running one.
                closeQuietly(callerAudio);
                try { callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); } catch (RemoteException ignored) {}
                DiagnosticLog.recognizerError(getFilesDir(), SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
                return;
            }
            NativeCallbacks target = claim(caller, callback);
            startListeningFromCallerAudio(recognizerIntent, callback, callerAudio, target);
            return;
        }

        // Android can deliver a replacement request before the dead caller's
        // cancellation reaches us (for example during a fast app/editor
        // switch). Retire the previous microphone request first so the new one
        // never receives a false ERROR_AUDIO from the stale process-wide owner.
        // A caller-audio job in the other slot is left alone.
        Callback previous = mic.callback;
        retire(mic, "cancel");
        try {
            cancelNative();
        } catch (Throwable t) {
            Log.e(TAG, "stale-session cleanup failed", t);
        }
        if (previous != null && previous != callback) {
            // Tell a still-living previous client, and free its framework session.
            try { previous.error(SpeechRecognizer.ERROR_CLIENT); } catch (RemoteException ignored) {}
        }
        NativeCallbacks target = claim(mic, callback);

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted — open the app to grant it");
            safeError(mic, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
            return;
        }

        try {
            startListening(target);
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
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
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
        if (callback == caller.callback) {
            retire(caller, "cancel");
            try {
                cancelCallerAudio();
            } catch (Throwable t) {
                Log.e(TAG, "cancelCallerAudio failed", t);
            }
            return;
        }
        if (callback != mic.callback) return;
        retire(mic, "cancel");
        try {
            cancelNative();
        } catch (Throwable t) {
            Log.e(TAG, "cancel failed", t);
        }
    }

    @Override
    public void onDestroy() {
        retire(mic, "destroy");
        retire(caller, "destroy");
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
        DiagnosticLog.event(getFilesDir(), "callback_delivery", "process=service,reason=" + reason);
        retire(slot, reason);
    }

    private void deliverError(Slot slot, Callback cb, int errorCode) {
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
        public void onReadyForSpeech() { deliver(() -> deliverReadyForSpeech(callback)); }
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
