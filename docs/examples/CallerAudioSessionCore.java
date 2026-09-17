package docs.examples;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Platform-neutral lifecycle core for the caller-audio Android example.
 * Android adapters own AudioRecord, ParcelFileDescriptor, SpeechRecognizer,
 * and main-thread dispatch; this class owns the race and teardown contract.
 */
public final class CallerAudioSessionCore {
    public interface Recorder {
        void stop();
        void release();
    }

    public interface Resource {
        void close();
    }

    public interface Recognizer {
        void stopListening();
        void cancel();
        void destroy();
    }

    public interface MainQueue {
        void post(Runnable action);
    }

    private enum State {
        NEW, STREAMING, WAITING_RESULT, FAILURE_PENDING, COMPLETED, CANCELLED, DESTROYED
    }

    private final Recognizer recognizer;
    private final MainQueue main;
    private final Consumer<String> onFinal;
    private final IntConsumer onFailure;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final Object captureLock = new Object();

    private Recorder recorder;
    private Resource providerEnd;
    private Resource writeEnd;

    public CallerAudioSessionCore(
            Recognizer recognizer,
            MainQueue main,
            Consumer<String> onFinal,
            IntConsumer onFailure) {
        this.recognizer = Objects.requireNonNull(recognizer);
        this.main = Objects.requireNonNull(main);
        this.onFinal = Objects.requireNonNull(onFinal);
        this.onFailure = Objects.requireNonNull(onFailure);
    }

    public void start(Recorder openedRecorder, Resource openedProviderEnd,
                      Resource openedWriteEnd, Runnable startRecognizer) {
        if (!state.compareAndSet(State.NEW, State.STREAMING)) {
            throw new IllegalStateException("session already started");
        }
        synchronized (captureLock) {
            recorder = openedRecorder;
            providerEnd = openedProviderEnd;
            writeEnd = openedWriteEnd;
        }
        try {
            startRecognizer.run();
        } catch (RuntimeException | Error error) {
            state.set(State.CANCELLED);
            releaseCapture();
            safely(recognizer::cancel);
            throw error;
        }
    }

    public boolean isStreaming() {
        return state.get() == State.STREAMING;
    }

    public void stop() {
        if (!state.compareAndSet(State.STREAMING, State.WAITING_RESULT)) return;
        releaseCapture();
        main.post(() -> {
            if (state.get() != State.WAITING_RESULT) return;
            try {
                recognizer.stopListening();
            } catch (Throwable ignored) {
                onError(5); // SpeechRecognizer.ERROR_CLIENT
            }
        });
    }

    public void cancel() {
        State previous = state.getAndSet(State.CANCELLED);
        if (previous == State.DESTROYED || previous == State.CANCELLED) return;
        releaseCapture();
        safely(recognizer::cancel);
    }

    public void destroy() {
        State previous = state.getAndSet(State.DESTROYED);
        if (previous == State.DESTROYED) return;
        releaseCapture();
        safely(recognizer::cancel);
        safely(recognizer::destroy);
    }

    /** Called by either the recorder read loop or pipe writer on a worker thread. */
    public void onCaptureFailure(int error) {
        if (!state.compareAndSet(State.STREAMING, State.FAILURE_PENDING)) return;
        releaseCapture();
        main.post(() -> {
            if (state.compareAndSet(State.FAILURE_PENDING, State.COMPLETED)) {
                onFailure.accept(error);
            }
        });
    }

    public void onResults(String text, boolean originCurrent) {
        if (!completeActive()) return;
        releaseCapture();
        if (originCurrent && text != null && !text.isBlank()) onFinal.accept(text);
    }

    public void onError(int error) {
        if (!completeActive()) return;
        releaseCapture();
        onFailure.accept(error);
    }

    private boolean completeActive() {
        while (true) {
            State current = state.get();
            if (current != State.STREAMING && current != State.WAITING_RESULT) return false;
            if (state.compareAndSet(current, State.COMPLETED)) return true;
        }
    }

    private void releaseCapture() {
        Recorder oldRecorder;
        Resource oldWriteEnd;
        Resource oldProviderEnd;
        synchronized (captureLock) {
            oldRecorder = recorder;
            oldWriteEnd = writeEnd;
            oldProviderEnd = providerEnd;
            recorder = null;
            writeEnd = null;
            providerEnd = null;
        }
        if (oldRecorder != null) {
            safely(oldRecorder::stop);
            safely(oldRecorder::release);
        }
        if (oldWriteEnd != null) safely(oldWriteEnd::close);
        if (oldProviderEnd != null) safely(oldProviderEnd::close);
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (Throwable ignored) {
            // Teardown is best-effort and idempotent; preserve the first outcome.
        }
    }
}
