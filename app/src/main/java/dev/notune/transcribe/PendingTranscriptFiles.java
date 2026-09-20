package dev.notune.transcribe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Context-free, lock-safe file operations for the one recovery record. */
final class PendingTranscriptFiles {
    private static final String FILE_NAME = "pending_ime_transcript";
    private static final String TEMP_NAME = FILE_NAME + ".tmp";
    private static final String LOCK_NAME = FILE_NAME + ".lock";

    private PendingTranscriptFiles() { }

    static boolean saveIfEmpty(File directory, String text) {
        try {
            return withLock(directory, lockedDirectory -> {
                File target = new File(lockedDirectory, FILE_NAME);
                if (target.isFile()) {
                    try {
                        PendingTranscriptRecord.decode(Files.readAllBytes(target.toPath()));
                        return false;
                    } catch (IOException damaged) {
                        if (!target.delete()) return false;
                    }
                }
                byte[] encoded = PendingTranscriptRecord.encode(
                        text, System.currentTimeMillis());
                File temporary = new File(lockedDirectory, TEMP_NAME);
                try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                    output.write(encoded);
                    output.flush();
                    output.getFD().sync();
                }
                try {
                    Files.move(temporary.toPath(), target.toPath(),
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicUnavailable) {
                    Files.move(temporary.toPath(), target.toPath());
                }
                return true;
            });
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    static PendingTranscriptRecord.Entry read(File directory) {
        try {
            return withLock(directory, lockedDirectory -> {
                File target = new File(lockedDirectory, FILE_NAME);
                if (!target.isFile()) return null;
                return PendingTranscriptRecord.decode(Files.readAllBytes(target.toPath()));
            });
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    static boolean hasRecord(File directory) {
        try {
            return withLock(directory, lockedDirectory ->
                    new File(lockedDirectory, FILE_NAME).isFile());
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    static boolean hasRecordFast(File directory) {
        return directory != null && new File(directory, FILE_NAME).isFile();
    }

    static boolean clear(File directory) {
        try {
            return withLock(directory, lockedDirectory -> {
                File target = new File(lockedDirectory, FILE_NAME);
                return !target.exists() || target.delete();
            });
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    static File recordFileForTest(File directory) {
        return new File(directory, FILE_NAME);
    }

    private interface Operation<T> { T run(File directory) throws IOException; }

    private static synchronized <T> T withLock(File directory, Operation<T> operation)
            throws IOException {
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IOException("private storage unavailable");
        }
        File lockFile = new File(directory, LOCK_NAME);
        try (RandomAccessFile randomAccess = new RandomAccessFile(lockFile, "rw");
             FileChannel channel = randomAccess.getChannel();
             FileLock ignored = channel.lock()) {
            return operation.run(directory);
        }
    }
}
