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
    public static final long MAX_BYTES = 1024L * 1024L;
    public static final long BASIC_MAX_BYTES = 64L * 1024L;
    /**
     * Compact below the hard cap so subsequent events can append without
     * rewriting the file again immediately. The log is diagnostic-only and
     * intentionally retains recent complete records rather than every event.
     */
    private static final long COMPACT_TO_BYTES = 768L * 1024L;
    private static final long BASIC_COMPACT_TO_BYTES = 48L * 1024L;
    private static final String ENABLED_FILE = "diagnostic_logging";
    private static final String LOG_FILE = "diagnostic.log";
    private static final String LOCK_FILE = ".diagnostic.lock";
    private static final String ENHANCED_FILE = "diagnostic_troubleshooting";
    public static final long TROUBLESHOOTING_DURATION_MS = 60L * 60L * 1000L;
    public static final long TROUBLESHOOTING_DAY_MS = 24L * TROUBLESHOOTING_DURATION_MS;
    private static final Pattern SAFE_DETAIL = Pattern.compile(
            "(?:[a-z][a-z0-9_]*=[a-zA-Z0-9_.:/-]+)(?:,(?:[a-z][a-z0-9_]*=[a-zA-Z0-9_.:/-]+))*");
    private static final Set<String> EVENTS = new HashSet<>(Arrays.asList(
            "app_start", "app_stop", "app_lifecycle", "service_state",
            "ime_start", "ime_stop", "ime_state",
            "model_state", "model_provenance", "timing", "resource",
            "notification_state", "error_code", "recognition_outcome", "callback_delivery", "session", "pipeline", "window_plan"));
    private static final Pattern UNSIGNED_METADATA = Pattern.compile("[0-9]{1,12}");
    private static final Pattern BATTERY_METADATA = Pattern.compile("[0-9]{1,3}");
    private static final Pattern THERMAL_METADATA = Pattern.compile("[0-6]");
    private static final Pattern ERROR_METADATA = Pattern.compile("E_[A-Z0-9_]{1,64}");
    private static final Set<String> STATES = tokens("start", "stop", "ready", "unknown", "error",
            "loading", "processing", "idle", "enabled", "disabled", "visible", "hidden",
            "created", "destroyed", "recording", "cancelled");
    private static final Set<String> ROUTES = tokens("ime", "microphone", "caller_audio");
    private static final Set<String> PHASES = tokens("capture", "processing", "delivery", "sample");
    private static final Set<String> MODEL_FAMILIES = tokens("unknown", "whisper", "parakeet", "moonshine", "gigaam", "medasr");
    private static final Set<String> REASONS = tokens("start", "stop", "end", "cancel", "destroy", "error",
            "text", "empty_decode", "insufficient_audio", "input_error", "delivered", "retained", "discarded", "callback_failed");
    private static final Set<String> OUTCOMES = tokens("text", "insufficient_audio", "empty_decode", "input_error");
    private static final Set<String> SOURCES = tokens("bundled", "imported", "bundled_opt_in");
    private static final Set<String> ABIS = tokens("arm64-v8a", "armeabi-v7a", "x86", "x86_64");
    private static final ReentrantLock PROCESS_LOCK = new ReentrantLock();
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256), runnable -> {
                Thread thread = new Thread(runnable, "diagnostic-log-writer");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private DiagnosticLog() { }

    private static Set<String> tokens(String... values) {
        return java.util.Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private static void replaceMarker(File temporary, File target) throws IOException {
        try {
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

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
                replaceMarker(temporary, marker);
            } else {
                Files.deleteIfExists(marker.toPath());
                Files.deleteIfExists(new File(filesDir, ENHANCED_FILE).toPath());
            }
        });
    }

    /** Explicit indefinite consent, stored as a maximum expiry in the existing marker format. */
    public static final long TROUBLESHOOTING_FOREVER_MS = -1L;

    /** Separate opt-in lease; malformed, expired or clock-reversed values fail closed. */
    public static boolean isTroubleshootingEnabled(File filesDir) {
        if (!isEnabled(filesDir)) return false;
        File lease = new File(filesDir, ENHANCED_FILE);
        if (!lease.isFile() || lease.length() != 16) return false;
        try {
            ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(lease.toPath()));
            long started = bytes.getLong();
            long expires = bytes.getLong();
            boolean valid = validTroubleshootingLease(started, expires, System.currentTimeMillis());
            if (!valid) {
                // Re-read under the cross-process lock: never delete a newer
                // warning-confirmed lease that replaced our expired snapshot.
                IoAction expire = () -> {
                    if (lease.isFile() && lease.length() == 16) {
                        ByteBuffer current = ByteBuffer.wrap(Files.readAllBytes(lease.toPath()));
                        if (current.getLong() == started && current.getLong() == expires) {
                            Files.deleteIfExists(lease.toPath());
                        }
                    }
                };
                if (PROCESS_LOCK.isHeldByCurrentThread()) expire.run();
                else withLock(filesDir, expire);
            }
            return valid;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    static boolean validTroubleshootingLease(long started, long expires, long now) {
        return started > 0 && now >= started
                && (expires == Long.MAX_VALUE || (expires > started
                && validTroubleshootingDuration(expires - started) && now < expires));
    }

    private static boolean validTroubleshootingDuration(long durationMs) {
        return durationMs == TROUBLESHOOTING_DURATION_MS || durationMs == TROUBLESHOOTING_DAY_MS
                || durationMs == TROUBLESHOOTING_FOREVER_MS;
    }

    public static void setTroubleshootingEnabled(File filesDir, boolean enabled) throws IOException {
        setTroubleshootingEnabled(filesDir, enabled, TROUBLESHOOTING_DURATION_MS);
    }

    public static void setTroubleshootingEnabled(File filesDir, boolean enabled, long durationMs) throws IOException {
        if (enabled && !validTroubleshootingDuration(durationMs)) {
            throw new IllegalArgumentException("Choose one hour, 24 hours or forever");
        }
        filesDir.mkdirs();
        withLock(filesDir, () -> {
            File target = new File(filesDir, ENHANCED_FILE);
            if (!enabled) { Files.deleteIfExists(target.toPath()); return; }
            if (!isEnabled(filesDir)) throw new IOException("Basic logging must be enabled first");
            long now = System.currentTimeMillis();
            File temporary = new File(filesDir, ENHANCED_FILE + ".tmp");
            Files.write(temporary.toPath(), ByteBuffer.allocate(16).putLong(now)
                    .putLong(durationMs == TROUBLESHOOTING_FOREVER_MS ? Long.MAX_VALUE : now + durationMs).array());
            replaceMarker(temporary, target);
        });
    }

    public static void clear(File filesDir) throws IOException {
        filesDir.mkdirs();
        withLock(filesDir, () -> Files.deleteIfExists(log(filesDir).toPath()));
    }

    /**
     * Returns one locked, bounded snapshot for an explicit user export.
     *
     * The app never exposes the live private file. Callers copy these bytes to
     * a user-selected document or a temporary FileProvider share instead.
     */
    static byte[] snapshot(File filesDir) throws IOException {
        filesDir.mkdirs();
        byte[][] result = new byte[][] {new byte[0]};
        withLock(filesDir, () -> {
            File target = log(filesDir);
            if (!target.isFile()) return;
            long size = target.length();
            if (size < 0L || size > MAX_BYTES) {
                throw new IOException("Diagnostic log exceeds its retention bound");
            }
            result[0] = Files.readAllBytes(target.toPath());
        });
        return result[0];
    }

    public static void event(File filesDir, String event, String detail) {
        enqueue(filesDir, event, detail, false);
    }

    static void troubleshootingEvent(File filesDir, String event, String detail) {
        enqueue(filesDir, event, detail, true);
    }

    private static void enqueue(File filesDir, String event, String detail, boolean enhanced) {
        long occurredAt = System.currentTimeMillis();
        if (!EVENTS.contains(event) || !isSafeDetail(detail) || !isEnabled(filesDir)) {
            return;
        }
        if (enhanced && !isTroubleshootingEnabled(filesDir)) return;
        try {
            WRITER.execute(() -> writeEvent(filesDir, event, detail, enhanced, occurredAt));
        } catch (RejectedExecutionException ignored) {
            // The bounded queue drops excess diagnostics rather than affecting the app.
        }
    }

    /** Framework error numbers only; never log exception or recognition text. */
    static void recognizerError(File filesDir, int errorCode) {
        String code = errorCode >= 0 ? Integer.toString(errorCode) : "UNKNOWN";
        event(filesDir, "error_code", "process=service,code=E_RECOGNIZER_" + code);
    }

    static void recognitionOutcome(File filesDir, int reason, long audioMs) {
        String label;
        switch (reason) {
            case 0: label = "text"; break;
            case 1: label = "insufficient_audio"; break;
            case 2: label = "empty_decode"; break;
            case 3: label = "input_error"; break;
            default: return;
        }
        event(filesDir, "recognition_outcome", "process=service,outcome=" + label
                + ",audio_ms=" + Math.max(0L, Math.min(999999999999L, audioMs)));
    }

    private static void writeEvent(File filesDir, String event, String detail, boolean enhanced, long occurredAt) {
        try {
            filesDir.mkdirs();
            withLock(filesDir, () -> {
                if (!isEnabled(filesDir)) return;
                boolean detailed = isTroubleshootingEnabled(filesDir);
                if (enhanced && !detailed) return;
                long maxBytes = detailed ? MAX_BYTES : BASIC_MAX_BYTES;
                long compactToBytes = detailed ? COMPACT_TO_BYTES : BASIC_COMPACT_TO_BYTES;
                byte[] line = (occurredAt + " " + event + " " + detail + "\n")
                        .getBytes(StandardCharsets.UTF_8);
                File target = log(filesDir);
                long existingSize = target.isFile() ? target.length() : 0L;
                if (existingSize + line.length <= maxBytes) {
                    try (FileChannel channel = new RandomAccessFile(target, "rw").getChannel()) {
                        channel.position(existingSize);
                        writeFully(channel, ByteBuffer.wrap(line));
                    }
                    return;
                }

                byte[] existing = target.isFile() ? Files.readAllBytes(target.toPath()) : new byte[0];
                int keep = (int) Math.min(existing.length,
                        Math.max(0L, compactToBytes - line.length));
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
                    safe = STATES.contains(value);
                    break;
                case "elapsed_ms":
                case "duration_ms":
                case "audio_ms":
                case "paused_ms":
                case "cpu_threads":
                case "version_code":
                case "memory_kb":
                case "session_id":
                case "cpu_ms":
                case "stop_wait_ms":
                case "prepare_ms":
                case "decode_ms":
                case "postprocess_ms":
                case "prepared_windows":
                case "planned_windows":
                case "raw_window_limit_ms":
                case "model_warm":
                    safe = UNSIGNED_METADATA.matcher(value).matches();
                    break;
                case "battery_pct":
                    safe = value.equals("unknown") || BATTERY_METADATA.matcher(value).matches();
                    break;
                case "thermal":
                    safe = value.equals("unknown") || THERMAL_METADATA.matcher(value).matches();
                    break;
                case "power":
                    safe = value.equals("battery") || value.equals("external") || value.equals("unknown");
                    break;
                case "route":
                    safe = ROUTES.contains(value);
                    break;
                case "phase":
                    safe = PHASES.contains(value);
                    break;
                case "model_family":
                    safe = MODEL_FAMILIES.contains(value);
                    break;
                case "reason":
                    safe = REASONS.contains(value);
                    break;
                case "outcome":
                    safe = OUTCOMES.contains(value);
                    break;
                case "code":
                    safe = ERROR_METADATA.matcher(value).matches();
                    break;
                case "source":
                    safe = SOURCES.contains(value);
                    break;
                case "abi":
                    safe = ABIS.contains(value);
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
