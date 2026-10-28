package dev.notune.transcribe;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One bounded transcription of an existing audio file, shared by the share screen, the
 * activity-result contract and the headless recognition-service request.
 *
 * <p>The decoder hands ten-second 16 kHz blocks to a native job that keeps one model window
 * plus overlap, so memory stays bounded for any duration. No interactive limit applies: no
 * initial-silence timeout, no stop-after-silence and no ten-minute cap. The job ends at the
 * decoder's end of stream, on failure, on {@link #cancel()}, or when neither decoding nor
 * inference advanced for {@link FileTranscriptionContract#STALL_TIMEOUT_MS}.
 *
 * <p>Exactly one of {@link Listener#onResult} and {@link Listener#onFailure} is delivered,
 * and none after {@link #cancel()}. Callbacks arrive on worker threads. Transcript text is
 * handed only to the listener: never to logs, notifications or diagnostics.
 */
final class FileTranscription extends ContextWrapper {
    private static final String TAG = "OfflineVoiceInput";
    private static final long WATCHDOG_INTERVAL_MS = 5_000L;

    static {
        try {
            NativeLibraries.load();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    interface Listener {
        /** A non-terminal native loading status, for display only (never content). */
        void onStatus(String status);
        /** The model is loaded and decoding begins. */
        void onReady();
        /** Cleaned transcript of the windows completed so far; only when partials were requested. */
        void onPartial(String text);
        /** Decoded samples at 16 kHz and the container duration (or -1). */
        void onProgress(long decodedSamples, long durationUs);
        /** All audio was read; the final window is being transcribed. */
        void onDecodeComplete();
        /** Final, nonblank transcript after the complete-job cleanup. */
        void onResult(String text, long audioMs, long computeMs);
        void onFailure(FileTranscriptionContract.Failure failure);
    }

    private final Uri uri;
    private final boolean partials;
    private final Listener listener;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean decodeStarted = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private volatile boolean cancelled;
    private volatile long lastProgressAt;
    private volatile long decodedSamples;
    private long startedAt;
    private long handle;

    FileTranscription(Context base, Uri uri, boolean partials, Listener listener) {
        super(base.getApplicationContext() != null ? base.getApplicationContext() : base);
        this.uri = uri;
        this.partials = partials;
        this.listener = listener;
    }

    /** Starts model loading; decoding follows on its own thread. Call once. */
    void start() {
        if (!started.compareAndSet(false, true)) return;
        startedAt = SystemClock.elapsedRealtime();
        lastProgressAt = startedAt;
        long created;
        try {
            created = nativeCreate(this, partials);
        } catch (Throwable t) {
            Log.e(TAG, "file job creation failed");
            created = 0;
        }
        synchronized (this) { handle = created; }
        if (created == 0) {
            fail(FileTranscriptionContract.Failure.ENGINE);
            return;
        }
        Thread watchdog = new Thread(this::watch, "transcribe-file-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * Stops decoding at the next block and the native job before its next window. A window
     * already inside the model finishes behind the engine lock, but nothing is delivered.
     */
    void cancel() {
        cancelled = true;
        terminal.set(true);
        release();
    }

    private synchronized long currentHandle() {
        return handle;
    }

    private void release() {
        long released;
        synchronized (this) {
            released = handle;
            handle = 0;
        }
        if (released != 0) {
            try { nativeRelease(released); } catch (Throwable t) { /* already gone */ }
        }
    }

    private void watch() {
        while (!terminal.get()) {
            try {
                Thread.sleep(WATCHDOG_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (FileTranscriptionContract.stalled(lastProgressAt, SystemClock.elapsedRealtime())) {
                Log.w(TAG, "file transcription made no progress; failing");
                fail(FileTranscriptionContract.Failure.ENGINE);
                return;
            }
        }
    }

    private void fail(FileTranscriptionContract.Failure failure) {
        if (cancelled || !terminal.compareAndSet(false, true)) return;
        release();
        listener.onFailure(failure);
    }

    // --- Called from native code (worker threads) ------------------------------------------

    public void onStatusUpdate(String status) {
        if (cancelled || terminal.get()) return;
        lastProgressAt = SystemClock.elapsedRealtime();
        if (status != null && status.startsWith("Ready")) {
            // A later "Ready" (e.g. after a settings reload) must not start a second decode.
            if (decodeStarted.compareAndSet(false, true)) {
                listener.onReady();
                Thread decoder = new Thread(this::decode, "transcribe-file");
                decoder.start();
            }
        } else if (NativeStatus.isError(status)) {
            fail(decodeStarted.get()
                    ? FileTranscriptionContract.Failure.ENGINE
                    : FileTranscriptionContract.Failure.NO_MODEL);
        } else if (!decodeStarted.get()) {
            listener.onStatus(status);
        }
    }

    public void onPartialText(String text) {
        if (cancelled || terminal.get()) return;
        lastProgressAt = SystemClock.elapsedRealtime();
        listener.onPartial(text);
    }

    public void onTextTranscribed(String text) {
        if (text == null || text.trim().isEmpty()) {
            fail(FileTranscriptionContract.Failure.NO_SPEECH);
            return;
        }
        if (cancelled || !terminal.compareAndSet(false, true)) return;
        release();
        listener.onResult(text, decodedSamples * 1000L / AudioFileDecoder.TARGET_RATE,
                SystemClock.elapsedRealtime() - startedAt);
    }

    // --- Decoder thread ---------------------------------------------------------------------

    private void decode() {
        AudioFileDecoder decoder = new AudioFileDecoder(this, uri);
        AtomicBoolean accepted = new AtomicBoolean(true);
        long produced;
        try {
            produced = decoder.decode((samples, length) -> {
                if (cancelled || terminal.get()) return false;
                long h = currentHandle();
                boolean keepGoing = h != 0 && nativePush(h, samples, length);
                if (!keepGoing) accepted.set(false);
                decodedSamples += length;
                lastProgressAt = SystemClock.elapsedRealtime();
                listener.onProgress(decodedSamples, decoder.durationUs());
                return keepGoing && !cancelled;
            }, () -> cancelled || terminal.get());
        } catch (IOException | RuntimeException e) {
            // Content-free: the exception may carry a provider path.
            Log.e(TAG, "Audio decoding failed");
            fail(FileTranscriptionContract.Failure.UNDECODABLE);
            return;
        }
        if (cancelled || terminal.get()) return;
        if (!accepted.get()) {
            // The native side already reported an error status, or the job was released.
            fail(FileTranscriptionContract.Failure.ENGINE);
            return;
        }
        if (produced == 0) {
            fail(FileTranscriptionContract.Failure.UNDECODABLE);
            return;
        }
        listener.onDecodeComplete();
        lastProgressAt = SystemClock.elapsedRealtime();
        long h = currentHandle();
        if (h == 0) return;
        try {
            nativeFinish(h);
        } catch (Throwable t) {
            Log.e(TAG, "file job finish failed");
            fail(FileTranscriptionContract.Failure.ENGINE);
            return;
        }
        // nativeFinish always reports a terminal callback unless the job was cancelled.
        if (!terminal.get() && !cancelled) fail(FileTranscriptionContract.Failure.ENGINE);
    }

    private static native long nativeCreate(FileTranscription owner, boolean partials);
    private static native void nativeRelease(long handle);
    private static native boolean nativePush(long handle, float[] samples, int length);
    private static native void nativeFinish(long handle);
}
