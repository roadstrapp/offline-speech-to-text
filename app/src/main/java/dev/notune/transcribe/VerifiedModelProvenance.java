package dev.notune.transcribe;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

/** Persists the install-time verification provenance used for trusted model UI labels. */
final class VerifiedModelProvenance {
    private static final String DIRECTORY = "model_provenance";

    private VerifiedModelProvenance() {}

    static boolean isVerified(File filesDir, File model, ModelDescriptor descriptor) {
        if (filesDir == null || model == null || descriptor == null
                || !descriptor.isVerifiedDownload() || !model.isFile()
                || model.length() != descriptor.byteSize
                || !descriptor.fileName.equals(model.getName())) {
            return false;
        }
        try {
            File expectedParent = new File(filesDir, "models").getCanonicalFile();
            if (!expectedParent.equals(model.getCanonicalFile().getParentFile())) return false;
            List<String> lines = Files.readAllLines(
                    marker(filesDir, descriptor.fileName).toPath(), StandardCharsets.UTF_8);
            return lines.equals(record(descriptor));
        } catch (IOException error) {
            return false;
        }
    }

    static void markVerified(File filesDir, File model, ModelDescriptor descriptor)
            throws IOException {
        if (!isExpectedModel(filesDir, model, descriptor)) {
            throw new IOException("verified model provenance is invalid");
        }
        File directory = new File(filesDir, DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("verified model provenance directory is unavailable");
        }
        File destination = marker(filesDir, descriptor.fileName);
        File staged = new File(directory, descriptor.fileName + ".verified.tmp");
        Files.write(staged.toPath(), record(descriptor), StandardCharsets.UTF_8);
        try {
            Files.move(staged.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(staged.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }
    static void forget(File filesDir, String fileName) throws IOException {
        if (!ModelInstaller.isSafeModelName(fileName)) return;
        Files.deleteIfExists(marker(filesDir, fileName).toPath());
    }

    private static boolean isExpectedModel(
            File filesDir, File model, ModelDescriptor descriptor) throws IOException {
        if (filesDir == null || model == null || descriptor == null
                || !descriptor.isVerifiedDownload()
                || !descriptor.fileName.equals(model.getName())
                || !model.isFile() || model.length() != descriptor.byteSize) {
            return false;
        }
        return new File(filesDir, "models").getCanonicalFile()
                .equals(model.getCanonicalFile().getParentFile());
    }

    private static File marker(File filesDir, String fileName) {
        return new File(new File(filesDir, DIRECTORY), fileName + ".verified");
    }

    private static List<String> record(ModelDescriptor descriptor) {
        return Arrays.asList(
                descriptor.id,
                descriptor.fileName,
                Long.toString(descriptor.byteSize),
                descriptor.sha256.toLowerCase(java.util.Locale.ROOT));
    }
}
