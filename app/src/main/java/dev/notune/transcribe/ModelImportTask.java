package dev.notune.transcribe;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

/** Copies one user-selected GGUF into private staging, then atomically installs it. */
final class ModelImportTask {
    private static final int BUFFER_BYTES = 1024 * 1024;

    enum Failure { DEFAULT_MODEL_MISMATCH, IMPORT_FAILED }

    interface InputSource {
        InputStream open() throws IOException;
    }

    interface Listener {
        void onProgress(long copied, long total);
        void onComplete(String installedName);
        void onFailure(Failure failure);
    }

    private ModelImportTask() {}

    static void start(File filesDir, String requestedName, long expectedSize,
                      InputSource source, Listener listener) {
        new Thread(() -> run(filesDir, requestedName, expectedSize, source, listener),
                "model-import").start();
    }

    static void runForTest(File filesDir, String requestedName, long expectedSize,
                           InputSource source, Listener listener) {
        run(filesDir, requestedName, expectedSize, source, listener);
    }

    private static void run(File filesDir, String requestedName, long expectedSize,
                            InputSource source, Listener listener) {
        File models = new File(filesDir, "models");
        if (!ModelInstaller.isSafeModelName(requestedName)
                || (!models.isDirectory() && !models.mkdirs())) {
            listener.onFailure(Failure.IMPORT_FAILED);
            return;
        }
        File staged = new File(models, requestedName + ".part");
        long copied = 0L;
        String digest = "";
        try (InputStream raw = source.open()) {
            if (raw == null) throw new IOException("model source is unavailable");
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (BufferedInputStream input = new BufferedInputStream(raw);
                 FileOutputStream file = new FileOutputStream(staged);
                 BufferedOutputStream output = new BufferedOutputStream(file, BUFFER_BYTES)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    sha256.update(buffer, 0, read);
                    copied += read;
                    listener.onProgress(copied, expectedSize);
                }
                output.flush();
                file.getChannel().force(true);
            }
            if (copied <= 0L) throw new IOException("model source is empty");
            digest = toHex(sha256.digest());
        } catch (Throwable error) {
            reportFailure(staged, listener, Failure.IMPORT_FAILED, error);
            return;
        }

        if (DefaultModelContract.FILE_NAME.equals(requestedName)
                && !DefaultModelContract.matches(requestedName, copied, digest)) {
            discard(staged);
            listener.onFailure(Failure.DEFAULT_MODEL_MISMATCH);
            return;
        }
        String installedName;
        try {
            installedName = ModelInstaller.installImported(filesDir, staged, requestedName);
        } catch (Throwable error) {
            reportFailure(staged, listener, Failure.IMPORT_FAILED, error);
            return;
        }
        listener.onComplete(installedName);
    }

    private static void reportFailure(File staged, Listener listener, Failure failure,
                                      Throwable error) {
        discard(staged);
        listener.onFailure(failure);
        if (error instanceof Error && !(error instanceof AssertionError)) {
            throw (Error) error;
        }
    }

    private static void discard(File staged) {
        try {
            Files.deleteIfExists(staged.toPath());
        } catch (IOException ignored) {
            // A hidden, unselected staging file cannot become an active model.
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
