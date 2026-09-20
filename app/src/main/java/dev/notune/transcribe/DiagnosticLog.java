package dev.notune.transcribe;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * App-private, opt-in diagnostics shared by the app and IME processes.
 *
 * The API accepts only fixed event names and token-shaped metadata. It has no
 * method that accepts audio, recognized speech, editor text, prompts, or
 * dictionary content, so those values cannot accidentally reach the file.
 */
public final class DiagnosticLog {
    public static final long MAX_BYTES = 64L * 1024L;
    /**
     * Compact below the hard cap so subsequent events can append without
     * rewriting the file again immediately. The log is diagnostic-only and
     * intentionally retains recent complete records rather than every event.
     */
    private static final long COMPACT_TO_BYTES = 48L * 1024L;
    private static final String ENABLED_FILE = "diagnostic_logging";
    private static final String LOG_FILE = "diagnostic.log";
    private static final String LOCK_FILE = ".diagnostic.lock";
    private static final Pattern SAFE_DETAIL = Pattern.compile(
            "(?:[a-z][a-z0-9_]*=[a-zA-Z0-9_.:/-]+)(?:,(?:[a-z][a-z0-9_]*=[a-zA-Z0-9_.:/-]+))*");
    private static final Set<String> EVENTS = new HashSet<>(Arrays.asList(
            "app_start", "app_stop", "app_lifecycle", "service_state",
            "ime_start", "ime_stop", "ime_state",
            "model_state", "model_provenance", "timing", "resource",
            "notification_state", "error_code"));
    private static final ReentrantLock PROCESS_LOCK = new ReentrantLock();
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256), runnable -> {
                Thread thread = new Thread(runnable, "diagnostic-log-writer");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private DiagnosticLog() { }

    public static boolean isEnabled(File filesDir) {
        return marker(filesDir).isFile();
    }

    public static void setEnabled(File filesDir, boolean enabled) throws IOException {
        filesDir.mkdirs();
        withLock(filesDir, () -> {
            File marker = marker(filesDir);
            if (enabled) {
                File temporary = new File(filesDir, ENABLED_FILE + ".tmp");
                Files.write(temporary.toPath(), new byte[0]);
                try {
                    Files.move(temporary.toPath(), marker.toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary.toPath(), marker.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.deleteIfExists(marker.toPath());
            }
        });
    }

    public static void clear(File filesDir) throws IOException {
        filesDir.mkdirs();
        withLock(filesDir, () -> Files.deleteIfExists(log(filesDir).toPath()));
    }

    public static void event(File filesDir, String event, String detail) {
        if (!EVENTS.contains(event) || !isSafeDetail(detail) || !isEnabled(filesDir)) {
            return;
        }
        try {
            WRITER.execute(() -> writeEvent(filesDir, event, detail));
        } catch (RejectedExecutionException ignored) {
            // The bounded queue drops excess diagnostics rather than affecting the app.
        }
    }

    private static void writeEvent(File filesDir, String event, String detail) {
        try {
            filesDir.mkdirs();
            withLock(filesDir, () -> {
                if (!isEnabled(filesDir)) return;
                byte[] line = (System.currentTimeMillis() + " " + event + " " + detail + "\n")
                        .getBytes(StandardCharsets.UTF_8);
                File target = log(filesDir);
                long existingSize = target.isFile() ? target.length() : 0L;
                if (existingSize + line.length <= MAX_BYTES) {
                    try (FileChannel channel = new RandomAccessFile(target, "rw").getChannel()) {
                        channel.position(existingSize);
                        writeFully(channel, ByteBuffer.wrap(line));
                    }
                    return;
                }

                byte[] existing = target.isFile() ? Files.readAllBytes(target.toPath()) : new byte[0];
                int keep = (int) Math.min(existing.length,
                        Math.max(0L, COMPACT_TO_BYTES - line.length));
                int start = existing.length - keep;
                while (start < existing.length && start > 0 && existing[start - 1] != '\n') {
                    start++;
                    keep--;
                }
                try (FileChannel channel = new RandomAccessFile(target, "rw").getChannel()) {
                    channel.truncate(0);
                    if (keep > 0) writeFully(channel, ByteBuffer.wrap(existing, start, keep));
                    writeFully(channel, ByteBuffer.wrap(line));
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // Diagnostics must never affect app or IME behavior.
        }
    }

    static boolean awaitIdleForTest(long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            try {
                Future<?> barrier = WRITER.submit(() -> { });
                barrier.get(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
                return true;
            } catch (RejectedExecutionException ignored) {
                Thread.yield();
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private static File marker(File filesDir) {
        return new File(filesDir, ENABLED_FILE);
    }

    private static File log(File filesDir) {
        return new File(filesDir, LOG_FILE);
    }

    private static boolean isSafeDetail(String detail) {
        if (detail == null || detail.length() > 240 || !SAFE_DETAIL.matcher(detail).matches()) {
            return false;
        }
        for (String field : detail.split(",")) {
            String[] pair = field.split("=", 2);
            String key = pair[0];
            String value = pair[1];
            boolean safe;
            switch (key) {
                case "process":
                    safe = value.equals("app") || value.equals("ime") || value.equals("service");
                    break;
                case "state":
                    safe = Arrays.asList("start", "stop", "ready", "unknown", "error",
                            "loading", "processing", "idle", "enabled", "disabled",
                            "visible", "hidden", "created", "destroyed").contains(value);
                    break;
                case "elapsed_ms":
                case "duration_ms":
                case "memory_kb":
                    safe = value.matches("[0-9]{1,12}");
                    break;
                case "code":
                    safe = value.matches("E_[A-Z0-9_]{1,64}");
                    break;
                case "source":
                    safe = Arrays.asList("bundled", "imported", "bundled_opt_in").contains(value);
                    break;
                case "abi":
                    safe = Arrays.asList("arm64-v8a", "armeabi-v7a", "x86", "x86_64").contains(value);
                    break;
                default:
                    safe = false;
            }
            if (!safe) return false;
        }
        return true;
    }

    private interface IoAction {
        void run() throws IOException;
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static void withLock(File filesDir, IoAction action) throws IOException {
        PROCESS_LOCK.lock();
        try {
            try (FileChannel channel = new RandomAccessFile(new File(filesDir, LOCK_FILE), "rw").getChannel();
                 FileLock ignored = channel.lock()) {
                action.run();
            }
        } finally {
            PROCESS_LOCK.unlock();
        }
    }
}
