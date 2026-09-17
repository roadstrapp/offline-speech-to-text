package docs.examples;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class CallerAudioLifecycleTest {
    public static void main(String[] args) {
        synchronousStartFailureDiscardsEverything();
        asynchronousCaptureFailuresDiscardAndReportOnce();
        stopFinalizesButCancelDiscards();
        resultsAndRecognizerErrorsCloseEverything();
        destroyFencesQueuedStopAndCallbacks();
    }

    private static void synchronousStartFailureDiscardsEverything() {
        Fixture f = new Fixture();
        try {
            f.session.start(f.recorder, f.provider, f.writer, () -> { throw new IllegalStateException("start"); });
            fail("start failure must propagate");
        } catch (IllegalStateException expected) {
            assertEquals("start", expected.getMessage(), "original failure is preserved");
        }
        f.assertCaptureClosed("synchronous start failure");
        assertEquals(1, f.recognizer.cancelCount, "synchronous start failure cancels recognizer");
        f.session.onResults("late", true);
        assertEquals(List.of(), f.finals, "late result after start failure is discarded");
    }

    private static void asynchronousCaptureFailuresDiscardAndReportOnce() {
        for (String path : List.of("read", "write")) {
            Fixture f = new Fixture();
            f.start();
            f.session.onCaptureFailure(3);
            f.assertCaptureClosed("async " + path + " failure");
            f.session.onResults("late", true);
            f.queue.drain();
            assertEquals(List.of(3), f.failures, path + " failure reports exactly once");
            assertEquals(List.of(), f.finals, path + " failure fences late results");
        }
    }

    private static void stopFinalizesButCancelDiscards() {
        Fixture stopped = new Fixture();
        stopped.start();
        stopped.session.stop();
        stopped.assertCaptureClosed("stop");
        assertEquals(0, stopped.recognizer.stopCount, "stopListening is queued");
        stopped.queue.drain();
        assertEquals(1, stopped.recognizer.stopCount, "stop requests a final result once");
        stopped.session.onResults("final", true);
        assertEquals(List.of("final"), stopped.finals, "stop preserves one final result");

        Fixture cancelled = new Fixture();
        cancelled.start();
        cancelled.session.cancel();
        cancelled.assertCaptureClosed("cancel");
        cancelled.session.onResults("late", true);
        assertEquals(1, cancelled.recognizer.cancelCount, "cancel reaches recognizer");
        assertEquals(List.of(), cancelled.finals, "cancel discards late results");
    }

    private static void resultsAndRecognizerErrorsCloseEverything() {
        Fixture results = new Fixture();
        results.start();
        results.session.onResults("accepted", true);
        results.assertCaptureClosed("results");
        results.session.onResults("duplicate", true);
        assertEquals(List.of("accepted"), results.finals, "only one current nonblank result is delivered");

        Fixture error = new Fixture();
        error.start();
        error.session.onError(5);
        error.assertCaptureClosed("recognizer error");
        error.session.onResults("late", true);
        assertEquals(List.of(5), error.failures, "recognizer error reports once");
        assertEquals(List.of(), error.finals, "recognizer error fences results");
    }

    private static void destroyFencesQueuedStopAndCallbacks() {
        Fixture stopped = new Fixture();
        stopped.start();
        stopped.session.stop();
        stopped.session.destroy();
        stopped.queue.drain();
        assertEquals(0, stopped.recognizer.stopCount, "destroy fences queued stopListening");
        assertEquals(1, stopped.recognizer.destroyCount, "destroy reaches recognizer once");

        Fixture failed = new Fixture();
        failed.start();
        failed.session.onCaptureFailure(3);
        failed.session.destroy();
        failed.queue.drain();
        assertEquals(List.of(), failed.failures, "destroy fences queued capture failure");
        failed.session.onResults("late", true);
        assertEquals(List.of(), failed.finals, "destroy fences late results");
    }

    private static final class Fixture {
        final FakeRecorder recorder = new FakeRecorder();
        final FakeCloseable provider = new FakeCloseable();
        final FakeCloseable writer = new FakeCloseable();
        final FakeRecognizer recognizer = new FakeRecognizer();
        final FakeQueue queue = new FakeQueue();
        final List<String> finals = new ArrayList<>();
        final List<Integer> failures = new ArrayList<>();
        final CallerAudioSessionCore session = new CallerAudioSessionCore(
                recognizer, queue, finals::add, failures::add);

        void start() { session.start(recorder, provider, writer, () -> {}); }

        void assertCaptureClosed(String path) {
            assertEquals(1, recorder.stopCount, path + " stops recorder once");
            assertEquals(1, recorder.releaseCount, path + " releases recorder once");
            assertEquals(1, provider.closeCount, path + " closes provider descriptor once");
            assertEquals(1, writer.closeCount, path + " closes writer descriptor once");
        }
    }

    private static final class FakeRecorder implements CallerAudioSessionCore.Recorder {
        int stopCount;
        int releaseCount;
        public void stop() { stopCount++; }
        public void release() { releaseCount++; }
    }

    private static final class FakeCloseable implements CallerAudioSessionCore.Resource {
        int closeCount;
        public void close() { closeCount++; }
    }

    private static final class FakeRecognizer implements CallerAudioSessionCore.Recognizer {
        int stopCount;
        int cancelCount;
        int destroyCount;
        public void stopListening() { stopCount++; }
        public void cancel() { cancelCount++; }
        public void destroy() { destroyCount++; }
    }

    private static final class FakeQueue implements CallerAudioSessionCore.MainQueue {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        public void post(Runnable action) { pending.add(action); }
        void drain() { while (!pending.isEmpty()) pending.remove().run(); }
    }

    private static void fail(String message) { throw new AssertionError(message); }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
