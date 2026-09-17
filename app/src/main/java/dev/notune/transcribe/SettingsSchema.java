package dev.notune.transcribe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Version marker for the app-private marker-file settings.
 *
 * Migration is deliberately additive: unknown files, imported models, and
 * user transcription data are never enumerated or deleted. A process-safe
 * lock and same-directory atomic replacement make an interrupted write safe
 * for both the app and its dedicated IME process.
 */
final class SettingsSchema {
    static final int CURRENT = 1;
    private static final String VERSION_FILE = ".settings_schema";
    private static final String TEMP_FILE = ".settings_schema.tmp";
    private static final String LOCK_FILE = ".settings_schema.lock";

    private SettingsSchema() {}

    static int migrate(File filesDir) throws IOException {
        if (!filesDir.exists() && !filesDir.mkdirs()) {
            throw new IOException("could not create app files directory");
        }
        File lockFile = new File(filesDir, LOCK_FILE);
        try (FileOutputStream lockStream = new FileOutputStream(lockFile, true)) {
            FileLock lock;
            try {
                lock = lockStream.getChannel().tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                // Another app process owns the additive migration. Never wait
                // during startup; that process will publish the version atomically.
                return readVersion(new File(filesDir, VERSION_FILE));
            }
            try (FileLock ignored = lock) {
            File versionFile = new File(filesDir, VERSION_FILE);
            int stored = readVersion(versionFile);
            if (stored >= CURRENT) {
                return stored;
            }

            File temp = new File(filesDir, TEMP_FILE);
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                out.write(Integer.toString(CURRENT).getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            try {
                Files.move(temp.toPath(), versionFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), versionFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return CURRENT;
            }
        }
    }

    private static int readVersion(File file) {
        if (!file.isFile()) return 0;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[32];
            int count = in.read(bytes);
            if (count <= 0) return 0;
            int value = Integer.parseInt(new String(bytes, 0, count,
                    StandardCharsets.UTF_8).trim());
            return Math.max(value, 0);
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }
}
