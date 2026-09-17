package dev.notune.transcribe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantLock;

/** Persists the active imported GGUF selection after a successful import. */
final class ImportedModelSelection {
    private static final String ACTIVE_MODEL_FILE = "active_model";
    static final ReentrantLock PROCESS_LOCK = new ReentrantLock();

    private ImportedModelSelection() {}

    static void activate(File filesDir, String modelName) throws IOException {
        if (!ModelInstaller.isSafeModelName(modelName)) {
            throw new IllegalArgumentException("modelName must be a safe GGUF filename");
        }
        PROCESS_LOCK.lock();
        try {
            File lockFile = new File(filesDir, "model_install.lock");
            try (RandomAccessFile lockAccess = new RandomAccessFile(lockFile, "rw");
                 FileChannel lockChannel = lockAccess.getChannel();
                 FileLock ignored = lockChannel.lock()) {
                activateWhileLocked(filesDir, modelName);
            }
        } finally {
            PROCESS_LOCK.unlock();
        }
    }

    static void activateWhileLocked(File filesDir, String modelName) throws IOException {
        File activeModel = new File(filesDir, ACTIVE_MODEL_FILE);
        File temporary = new File(filesDir, ACTIVE_MODEL_FILE + ".tmp");
        byte[] value = modelName.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(value);
            out.getChannel().force(true);
        }
        try {
            Files.move(temporary.toPath(), activeModel.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary.toPath(), activeModel.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void deactivate(File filesDir) throws IOException {
        PROCESS_LOCK.lock();
        try {
            File lockFile = new File(filesDir, "model_install.lock");
            try (RandomAccessFile lockAccess = new RandomAccessFile(lockFile, "rw");
                 FileChannel lockChannel = lockAccess.getChannel();
                 FileLock ignored = lockChannel.lock()) {
                Files.deleteIfExists(new File(filesDir, ACTIVE_MODEL_FILE).toPath());
                Files.deleteIfExists(new File(filesDir, ACTIVE_MODEL_FILE + ".tmp").toPath());
            }
        } finally {
            PROCESS_LOCK.unlock();
        }
    }
}
