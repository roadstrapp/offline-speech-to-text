package dev.notune.transcribe;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executable downloader/installer trust-boundary regression tests. */
public final class ModelDownloadBoundaryTest {
    private static final byte[] APPROVED = "approved model bytes".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        disallowedRedirectAndRedirectLimitFailClosed();
        ignoredRangeAndMismatchedValidatorRestartFromZero();
        truncatedAndOversizedBodiesNeverActivate();
        midStreamCancellationDiscardsStaging();
        concurrentOwnershipFailsWithoutTouchingStaging();
        conflictingDestinationIsRecoverableAndAtomicActivationSucceeds();
        staleStagingIsSweptButFreshResumeStateSurvives();
    }

    private static void disallowedRedirectAndRedirectLimitFailClosed() throws Exception {
        Path files = Files.createTempDirectory("ostt-redirect-test");
        ScriptedConnections disallowed = new ScriptedConnections()
                .add(302, new byte[0], headers("Location", "https://evil.example/model.gguf"));
        Result result = run(files, descriptor(APPROVED), disallowed, new AtomicBoolean());
        assertEquals("Download host is not trusted.", result.failure,
                "redirect host family is revalidated before follow");
        assertFalse(Files.exists(files.resolve("models/verified.gguf")),
                "disallowed redirect never installs");

        ScriptedConnections loop = new ScriptedConnections();
        for (int i = 0; i < 6; i++) {
            loop.add(302, new byte[0], headers("Location",
                    "https://huggingface.co/example/redirect-" + i));
        }
        result = run(Files.createTempDirectory("ostt-redirect-limit-test"),
                descriptor(APPROVED), loop, new AtomicBoolean());
        assertEquals("Too many download redirects.", result.failure,
                "redirect chain is bounded");
    }

    private static void ignoredRangeAndMismatchedValidatorRestartFromZero() throws Exception {
        String prefix = "approved ";
        Path resumed = staged("ostt-range-resume-test", prefix, "stable-tag");
        ScriptedConnections matchingRange = new ScriptedConnections()
                .add(206, "model bytes".getBytes(StandardCharsets.UTF_8), headers(
                        "Content-Range", "bytes " + prefix.length() + "-"
                                + (APPROVED.length - 1) + "/" + APPROVED.length,
                        "ETag", "stable-tag"));
        Result result = run(resumed, descriptor(APPROVED), matchingRange, new AtomicBoolean());
        assertEquals("verified.gguf", result.complete,
                "matching validator and range append the exact tail");
        assertEquals("bytes=" + prefix.length() + "-",
                matchingRange.firstOpened.requestHeaders.get("Range"),
                "resume emits the exact Range request");
        assertEquals("stable-tag", matchingRange.firstOpened.requestHeaders.get("If-Range"),
                "resume binds the request to the saved validator");
        assertFileBytes(resumed.resolve("models/verified.gguf"), APPROVED,
                "successful 206 resume installs the exact reconstructed artifact");

        Path ignored = staged("ostt-range-ignored-test", "prefix", "old-tag");
        ScriptedConnections ignoredRange = new ScriptedConnections()
                .add(200, APPROVED, headers("ETag", "new-tag"));
        result = run(ignored, descriptor(APPROVED), ignoredRange, new AtomicBoolean());
        assertEquals("verified.gguf", result.complete,
                "server ignoring Range restarts from zero");
        assertFileBytes(ignored.resolve("models/verified.gguf"), APPROVED,
                "ignored Range cannot append duplicate bytes");

        Path mismatch = staged("ostt-validator-mismatch-test", "prefix", "old-tag");
        ScriptedConnections validatorMismatch = new ScriptedConnections()
                .add(206, "tail".getBytes(StandardCharsets.UTF_8), headers(
                        "Content-Range", "bytes 6-19/20", "ETag", "new-tag"))
                .add(200, APPROVED, headers("ETag", "new-tag"));
        result = run(mismatch, descriptor(APPROVED), validatorMismatch, new AtomicBoolean());
        assertEquals("verified.gguf", result.complete,
                "validator mismatch retries without Range");
        assertEquals(2, validatorMismatch.openCount,
                "validator mismatch performs one clean restart");
        assertFileBytes(mismatch.resolve("models/verified.gguf"), APPROVED,
                "clean restart installs only the exact artifact");
    }

    private static void truncatedAndOversizedBodiesNeverActivate() throws Exception {
        Path truncated = Files.createTempDirectory("ostt-truncated-test");
        Result result = run(truncated, descriptor(APPROVED), new ScriptedConnections()
                .add(200, "short".getBytes(StandardCharsets.UTF_8), headers("ETag", "v1")),
                new AtomicBoolean());
        assertEquals("Downloaded file failed integrity verification.", result.failure,
                "truncated response fails exact size and SHA verification");
        assertFalse(Files.exists(truncated.resolve("models/verified.gguf")),
                "truncated artifact never activates");

        byte[] oversized = new byte[APPROVED.length + 1];
        System.arraycopy(APPROVED, 0, oversized, 0, APPROVED.length);
        Path tooLarge = Files.createTempDirectory("ostt-oversized-test");
        result = run(tooLarge, descriptor(APPROVED), new ScriptedConnections()
                .add(200, oversized, headers("ETag", "v1")), new AtomicBoolean());
        assertEquals("Downloaded file failed integrity verification.", result.failure,
                "oversized response fails with a stable integrity error");
        assertFalse(Files.exists(tooLarge.resolve("models/.downloads/test-model.part")),
                "oversized staged bytes are discarded");
    }

    private static void midStreamCancellationDiscardsStaging() throws Exception {
        Path files = Files.createTempDirectory("ostt-midstream-cancel-test");
        AtomicBoolean cancelled = new AtomicBoolean();
        ScriptedConnections connections = new ScriptedConnections()
                .add(200, APPROVED, headers("ETag", "v1"), () -> cancelled.set(true));
        Result result = run(files, descriptor(APPROVED), connections, cancelled);
        assertEquals("Download cancelled.", result.failure,
                "mid-stream cancellation reaches a bounded terminal result");
        assertFalse(Files.exists(files.resolve("models/.downloads/test-model.part")),
                "mid-stream cancellation discards staged bytes");
        assertFalse(Files.exists(files.resolve("models/.downloads/test-model.validator")),
                "mid-stream cancellation discards validator state");
    }

    private static void concurrentOwnershipFailsWithoutTouchingStaging() throws Exception {
        Path files = staged("ostt-concurrent-owner-test", "resume", "etag");
        Path lockPath = files.resolve("models/.downloads/download.lock");
        try (RandomAccessFile access = new RandomAccessFile(lockPath.toFile(), "rw");
             FileChannel channel = access.getChannel();
             FileLock ignored = channel.lock()) {
            AtomicBoolean cancelled = new AtomicBoolean(true);
            Result result = run(files, descriptor(APPROVED), new ScriptedConnections(), cancelled);
            assertEquals("Download cancelled.", result.failure,
                    "a cancelled lock loser reports cancellation without taking ownership");
            assertEquals("resume", Files.readString(
                            files.resolve("models/.downloads/test-model.part")),
                    "cancelled lock loser does not delete the winning owner's staged bytes");
            assertEquals("etag", Files.readString(
                            files.resolve("models/.downloads/test-model.validator")),
                    "cancelled lock loser does not delete the winning owner's validator");
        }
    }

    private static void conflictingDestinationIsRecoverableAndAtomicActivationSucceeds()
            throws Exception {
        Path conflict = Files.createTempDirectory("ostt-destination-conflict-test");
        Files.createDirectories(conflict.resolve("models"));
        Files.writeString(conflict.resolve("models/verified.gguf"), "different");
        Result result = run(conflict, descriptor(APPROVED), new ScriptedConnections()
                .add(200, APPROVED, headers("ETag", "v1")), new AtomicBoolean());
        assertEquals("A different model already uses this filename. Remove it and retry.",
                result.failure, "destination conflict gives a recoverable instruction");
        assertFalse(Files.exists(conflict.resolve("active_model")),
                "conflicting destination is never activated");

        Path valid = Files.createTempDirectory("ostt-atomic-activation-test");
        result = run(valid, descriptor(APPROVED), new ScriptedConnections()
                .add(200, APPROVED, headers("ETag", "v1")), new AtomicBoolean());
        assertEquals("verified.gguf", result.complete, "exact artifact installs");
        assertEquals("verified.gguf", Files.readString(valid.resolve("active_model")),
                "activation pointer names the exact installed artifact");
        assertFileBytes(valid.resolve("models/verified.gguf"), APPROVED,
                "atomic activation preserves exact bytes");
    }

    private static void staleStagingIsSweptButFreshResumeStateSurvives() throws Exception {
        Path files = staged("ostt-stale-sweep-test", "stale", "stale-tag");
        Path part = files.resolve("models/.downloads/test-model.part");
        Path validator = files.resolve("models/.downloads/test-model.validator");
        long now = 10L * 24 * 60 * 60 * 1000;
        part.toFile().setLastModified(1);
        validator.toFile().setLastModified(1);
        ModelDownloadTask.sweepStaleDownloads(files.toFile(), now);
        assertFalse(Files.exists(part), "expired staged bytes are swept");
        assertFalse(Files.exists(validator), "expired validator is swept");

        Files.writeString(part, "fresh");
        Files.writeString(validator, "fresh-tag");
        part.toFile().setLastModified(now);
        validator.toFile().setLastModified(now);
        ModelDownloadTask.sweepStaleDownloads(files.toFile(), now);
        assertTrue(Files.exists(part), "fresh resumable bytes survive the sweep");
        assertTrue(Files.exists(validator), "fresh resume validator survives the sweep");
    }

    private static Result run(Path files, ModelDescriptor descriptor,
                              ScriptedConnections connections, AtomicBoolean cancelled) {
        Result result = new Result();
        ModelDownloadTask.runForTest(files.toFile(), descriptor, result, cancelled, connections);
        return result;
    }

    private static Path staged(String prefix, String bytes, String validator) throws Exception {
        Path files = Files.createTempDirectory(prefix);
        Path downloads = files.resolve("models/.downloads");
        Files.createDirectories(downloads);
        Files.writeString(downloads.resolve("test-model.part"), bytes);
        Files.writeString(downloads.resolve("test-model.validator"), validator);
        return files;
    }

    private static ModelDescriptor descriptor(byte[] bytes) throws Exception {
        return new ModelDescriptor("test-model", "Test model", "test", "test",
                "verified.gguf", bytes.length, sha256(bytes),
                "https://huggingface.co/example/model.gguf",
                "https://huggingface.co/example", "test-license",
                "https://example.invalid/license", Collections.singleton("arm64-v8a"),
                false, false, Collections.singleton("en"),
                ModelDescriptor.Capability.SUPPORTED, ModelDescriptor.Capability.UNSUPPORTED,
                ModelDescriptor.Capability.UNKNOWN, ModelDescriptor.Capability.UNKNOWN,
                1, 1, ModelDescriptor.Provenance.EXACT_PINNED);
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            value.append(String.format("%02x", b & 0xff));
        }
        return value.toString();
    }

    private static Map<String, String> headers(String... values) {
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) headers.put(values[i], values[i + 1]);
        return headers;
    }

    private static final class Result implements ModelDownloadTask.Listener {
        String complete;
        String failure;
        @Override public void onStarted(ModelDownloadTask.Handle handle) {}
        @Override public void onProgress(long downloaded, long total) {}
        @Override public void onComplete(String installedName) { complete = installedName; }
        @Override public void onFailure(String reason) { failure = reason; }
    }

    private static final class ScriptedConnections implements ModelDownloadTask.ConnectionFactory {
        private final ArrayDeque<FakeConnection> connections = new ArrayDeque<>();
        int openCount;
        FakeConnection firstOpened;
        ScriptedConnections add(int code, byte[] body, Map<String, String> headers) throws Exception {
            return add(code, body, headers, null);
        }
        ScriptedConnections add(int code, byte[] body, Map<String, String> headers,
                                Runnable onFirstRead) throws Exception {
            connections.add(new FakeConnection(new URL("https://huggingface.co/fake"),
                    code, body, headers, onFirstRead));
            return this;
        }
        @Override public HttpURLConnection open(URL url) throws IOException {
            openCount++;
            FakeConnection connection = connections.poll();
            if (connection == null) throw new IOException("unexpected connection");
            if (firstOpened == null) firstOpened = connection;
            return connection;
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final int code;
        private final byte[] body;
        private final Map<String, String> headers;
        private final Map<String, String> requestHeaders = new HashMap<>();
        private final Runnable onFirstRead;
        FakeConnection(URL url, int code, byte[] body, Map<String, String> headers,
                       Runnable onFirstRead) {
            super(url);
            this.code = code;
            this.body = body;
            this.headers = headers;
            this.onFirstRead = onFirstRead;
        }
        @Override public int getResponseCode() { return code; }
        @Override public String getHeaderField(String name) { return headers.get(name); }
        @Override public void setRequestProperty(String name, String value) {
            requestHeaders.put(name, value);
        }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(body) {
                private boolean first = true;
                @Override public synchronized int read(byte[] target, int offset, int length) {
                    int read = super.read(target, offset, length);
                    if (read > 0 && first) {
                        first = false;
                        if (onFirstRead != null) onFirstRead.run();
                    }
                    return read;
                }
            };
        }
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }

    private static void assertFileBytes(Path path, byte[] expected, String message) throws Exception {
        byte[] actual = Files.readAllBytes(path);
        if (!java.util.Arrays.equals(expected, actual)) throw new AssertionError(message);
    }
    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void assertFalse(boolean value, String message) {
        if (value) throw new AssertionError(message);
    }
}
