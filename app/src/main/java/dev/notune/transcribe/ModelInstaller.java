package dev.notune.transcribe;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Serializes immutable model installation and atomic active-pointer changes across processes. */
final class ModelInstaller {
    /** Where {@linkplain ModelDescriptor#auxiliary auxiliary} models live, under filesDir. */
    static final String AUXILIARY_DIRECTORY = "aux-models";

    private ModelInstaller() {}

    static String installVerified(File filesDir, File staged, ModelDescriptor descriptor)
            throws IOException {
        return installVerified(filesDir, staged, descriptor, () -> false);
    }

    static String installVerified(File filesDir, File staged, ModelDescriptor descriptor,
                                  BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        if (!staged.isFile()) throw new IOException("staged model is missing");
        if (descriptor == null || !descriptor.isVerifiedDownload()
                || !isSafeModelName(descriptor.fileName)) {
            throw new IOException("verified model descriptor is invalid");
        }
        if (staged.length() != descriptor.byteSize
                || !descriptor.sha256.equalsIgnoreCase(sha256(staged, cancelled))) {
            throw new IOException("staged model failed integrity verification");
        }
        checkCancelled(cancelled);
        File modelsDir = new File(filesDir, descriptor.auxiliary ? AUXILIARY_DIRECTORY : "models");
        if (!modelsDir.isDirectory() && !modelsDir.mkdirs()) {
            throw new IOException("model directory is unavailable");
        }
        ImportedModelSelection.PROCESS_LOCK.lock();
        try {
            File lockFile = new File(filesDir, "model_install.lock");
            try (RandomAccessFile lockAccess = new RandomAccessFile(lockFile, "rw");
                 FileChannel lockChannel = lockAccess.getChannel();
                 FileLock ignored = lockChannel.lock()) {
            checkCancelled(cancelled);
            File destination = new File(modelsDir, descriptor.fileName);
            if (destination.exists()) {
                if (destination.length() != descriptor.byteSize
                        || !descriptor.sha256.equalsIgnoreCase(sha256(destination, cancelled))) {
                    throw new IOException(
                            "A different model already uses this filename. Remove it and retry.");
                }
                Files.deleteIfExists(staged.toPath());
            } else {
                try {
                    Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(staged.toPath(), destination.toPath());
                }
            }
            if (!descriptor.auxiliary) {
                VerifiedModelProvenance.markVerified(filesDir, destination, descriptor);
                ImportedModelSelection.activateWhileLocked(filesDir, destination.getName());
            }
            return destination.getName();
            }
        } finally {
            ImportedModelSelection.PROCESS_LOCK.unlock();
        }
    }

    static boolean isSafeModelName(String name) {
        return name != null && name.length() <= 200 && name.equals(name.trim())
                && name.toLowerCase(Locale.ROOT).endsWith(".gguf")
                && new File(name).getName().equals(name)
                && name.indexOf('\0') < 0;
    }

    static String installImported(File filesDir, File staged, String requestedName)
            throws IOException {
        if (!staged.isFile() || !isSafeModelName(requestedName)) {
            throw new IOException("invalid staged model");
        }
        File modelsDir = new File(filesDir, "models");
        if (!modelsDir.isDirectory() && !modelsDir.mkdirs()) {
            throw new IOException("model directory is unavailable");
        }
        ImportedModelSelection.PROCESS_LOCK.lock();
        try {
            File lockFile = new File(filesDir, "model_install.lock");
            try (RandomAccessFile lockAccess = new RandomAccessFile(lockFile, "rw");
                 FileChannel lockChannel = lockAccess.getChannel();
                 FileLock ignored = lockChannel.lock()) {
            File destination = uniqueDestination(modelsDir, requestedName);
            try {
                Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staged.toPath(), destination.toPath());
            }
            VerifiedModelProvenance.forget(filesDir, destination.getName());
            ImportedModelSelection.activateWhileLocked(filesDir, destination.getName());
            return destination.getName();
            }
        } finally {
            ImportedModelSelection.PROCESS_LOCK.unlock();
        }
    }

    private static File uniqueDestination(File directory, String requestedName) throws IOException {
        File first = new File(directory, requestedName);
        if (!first.exists()) return first;
        int dot = requestedName.toLowerCase(Locale.ROOT).lastIndexOf(".gguf");
        String stem = dot > 0 ? requestedName.substring(0, dot) : requestedName;
        for (int suffix = 2; suffix < 1000; suffix++) {
            File candidate = new File(directory, stem + "-" + suffix + ".gguf");
            if (!candidate.exists()) return candidate;
        }
        throw new IOException("too many models use this filename");
    }

    private static String sha256(File file, BooleanSupplier cancelled) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new IOException("Download cancelled.");
    }
}
