package dev.notune.transcribe;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Executable tests for the manual-import storage boundary and retained lifecycle. */
public final class ModelImportTaskTest {
    public static void main(String[] args) throws Exception {
        customImportInstallsAndActivatesAtomically();
        defaultModelMismatchFailsClosed();
        unavailableAndEmptySourcesLeaveNoStaging();
        unsafeNamesAndUnexpectedErrorsFailClosed();
        lifecycleSurvivesObserverReplacement();
    }

    private static void customImportInstallsAndActivatesAtomically() throws Exception {
        Path files = Files.createTempDirectory("ostt-manual-import-test");
        byte[] model = "custom model bytes".getBytes(StandardCharsets.UTF_8);
        Result result = run(files, "custom.gguf", model.length,
                () -> new ByteArrayInputStream(model));

        assertEquals("custom.gguf", result.installed,
                "a valid custom import returns its installed filename");
        assertEquals("custom.gguf", Files.readString(files.resolve("active_model")),
                "a completed import atomically becomes active");
        assertEquals("custom model bytes", Files.readString(
                        files.resolve("models/custom.gguf")),
                "the installed bytes exactly match the selected source");
        assertEquals((long) model.length, result.lastProgress,
                "progress reaches the exact copied byte count");
    }

    private static void defaultModelMismatchFailsClosed() throws Exception {
        Path files = Files.createTempDirectory("ostt-default-import-mismatch-test");
        byte[] wrong = "not the pinned model".getBytes(StandardCharsets.UTF_8);
        Result result = run(files, DefaultModelContract.FILE_NAME, wrong.length,
                () -> new ByteArrayInputStream(wrong));

        assertEquals(ModelImportTask.Failure.DEFAULT_MODEL_MISMATCH, result.failure,
                "the reserved default filename requires the pinned size and digest");
        assertEquals(false, Files.exists(files.resolve("active_model")),
                "a mismatched default model is never activated");
        assertEquals(false, Files.exists(files.resolve(
                        "models/" + DefaultModelContract.FILE_NAME + ".part")),
                "mismatched staging bytes are discarded");
    }

    private static void unavailableAndEmptySourcesLeaveNoStaging() throws Exception {
        Path unavailable = Files.createTempDirectory("ostt-unavailable-import-test");
        Result missing = run(unavailable, "missing.gguf", -1L, () -> null);
        assertEquals(ModelImportTask.Failure.IMPORT_FAILED, missing.failure,
                "an unavailable content source fails explicitly");
        assertEquals(false, Files.exists(unavailable.resolve("models/missing.gguf.part")),
                "an unavailable source leaves no staged file");

        Path empty = Files.createTempDirectory("ostt-empty-import-test");
        Result zero = run(empty, "empty.gguf", 0L,
                () -> new ByteArrayInputStream(new byte[0]));
        assertEquals(ModelImportTask.Failure.IMPORT_FAILED, zero.failure,
                "an empty file is not accepted as a model");
        assertEquals(false, Files.exists(empty.resolve("models/empty.gguf.part")),
                "empty staging is removed after failure");
    }

    private static void unsafeNamesAndUnexpectedErrorsFailClosed() throws Exception {
        Path unsafe = Files.createTempDirectory("ostt-unsafe-import-test");
        Result traversal = run(unsafe, "../outside.gguf", 4L,
                () -> new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));
        assertEquals(ModelImportTask.Failure.IMPORT_FAILED, traversal.failure,
                "a traversal filename fails before staging");
        Result wrongExtension = run(unsafe, "not-a-model.txt", 4L,
                () -> new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));
        assertEquals(ModelImportTask.Failure.IMPORT_FAILED, wrongExtension.failure,
                "a non-GGUF filename fails before staging");
        assertEquals(false, Files.exists(unsafe.resolve("active_model")),
                "unsafe names can never activate a model");

        Path crashed = Files.createTempDirectory("ostt-import-error-test");
        Result unexpected = run(crashed, "error.gguf", 4L, () -> {
            throw new AssertionError("synthetic worker failure");
        });
        assertEquals(ModelImportTask.Failure.IMPORT_FAILED, unexpected.failure,
                "a non-fatal worker Error still closes the lifecycle");
        assertEquals(false, Files.exists(crashed.resolve("models/error.gguf.part")),
                "unexpected worker failure removes staging");
    }

    private static void lifecycleSurvivesObserverReplacement() {
        ModelImportLifecycle lifecycle = new ModelImportLifecycle();
        AtomicReference<ModelImportTask.Listener> task = new AtomicReference<>();
        List<ModelImportLifecycle.Snapshot> first = new ArrayList<>();
        List<ModelImportLifecycle.Snapshot> recreated = new ArrayList<>();
        ModelImportLifecycle.Observer firstObserver = first::add;
        ModelImportLifecycle.Observer recreatedObserver = recreated::add;

        lifecycle.attach(firstObserver);
        assertEquals(true, lifecycle.start("large.gguf", 100L, task::set),
                "the lifecycle starts the first import");
        assertEquals(false, lifecycle.start("other.gguf", 10L, listener -> {}),
                "a second import cannot replace the active owner");
        task.get().onProgress(40L, 100L);
        lifecycle.detach(firstObserver);
        lifecycle.attach(recreatedObserver);

        ModelImportLifecycle.Snapshot restored =
                recreated.get(recreated.size() - 1);
        assertEquals(ModelImportLifecycle.State.RUNNING, restored.state,
                "a recreated screen observes the running import");
        assertEquals(40L, restored.copied,
                "a recreated screen receives retained byte progress");
        assertEquals("large.gguf", restored.requestedName,
                "the retained state identifies the selected model");

        task.get().onComplete("large.gguf");
        ModelImportLifecycle.Snapshot complete =
                recreated.get(recreated.size() - 1);
        assertEquals(ModelImportLifecycle.State.COMPLETE, complete.state,
                "completion reaches the current observer");
        lifecycle.clearTerminal();
        assertEquals(ModelImportLifecycle.State.IDLE, lifecycle.snapshot().state,
                "consumed terminal state returns to idle");
    }

    private static Result run(Path files, String name, long size,
                              ModelImportTask.InputSource source) {
        Result result = new Result();
        ModelImportTask.runForTest(files.toFile(), name, size, source, result);
        return result;
    }

    private static final class Result implements ModelImportTask.Listener {
        long lastProgress;
        String installed;
        ModelImportTask.Failure failure;

        @Override public void onProgress(long copied, long total) {
            lastProgress = copied;
        }

        @Override public void onComplete(String installedName) {
            installed = installedName;
        }

        @Override public void onFailure(ModelImportTask.Failure reason) {
            failure = reason;
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
