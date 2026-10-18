package dev.notune.transcribe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/** Regressions for expired day consent, delayed writers, and bounded retention. */
public final class DiagnosticRegressionTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("lease")) dayLease();
        if (args.length == 0 || args[0].equals("timestamp")) eventTimestamp();
        if (args.length == 0 || args[0].equals("retention")) retention();
        System.out.println("Diagnostic regression tests passed");
    }

    private static void dayLease() {
        long start = 1_000_000L;
        long hour = 60 * 60 * 1000L;
        long end = start + 24 * hour;
        assert DiagnosticLog.validTroubleshootingLease(start, end, start + hour + 1)
                : "Explicit day consent must remain valid after one hour";
        assert !DiagnosticLog.validTroubleshootingLease(start, end, end);
        assert !DiagnosticLog.validTroubleshootingLease(start, end, start - 1);
        assert DiagnosticLog.validTroubleshootingLease(start, Long.MAX_VALUE, start + 48 * hour);
        assert !DiagnosticLog.validTroubleshootingLease(start, Long.MAX_VALUE, start - 1);
        assert !DiagnosticLog.validTroubleshootingLease(start, start + 2 * hour, start + hour);

    }

    private static void eventTimestamp() throws Exception {
        Path root = Files.createTempDirectory("ostt-event-time-");
        DiagnosticLog.setEnabled(root.toFile(), true);
        var field = DiagnosticLog.class.getDeclaredField("PROCESS_LOCK");
        field.setAccessible(true);
        ReentrantLock writerLock = (ReentrantLock) field.get(null);
        long before, released;
        writerLock.lock();
        try {
            before = System.currentTimeMillis();
            DiagnosticLog.event(root.toFile(), "timing", "elapsed_ms=777123");
            // Hold the actual writer lock: recording time must precede this delay.
            Thread.sleep(150);
            released = System.currentTimeMillis();
        } finally {
            writerLock.unlock();
        }
        assert DiagnosticLog.awaitIdleForTest(5000);
        String line = new String(DiagnosticLog.snapshot(root.toFile()), StandardCharsets.UTF_8);
        long recorded = Long.parseLong(line.substring(0, line.indexOf(' ')));
        assert recorded >= before && recorded < released
                : "Timestamp must describe the event, not delayed disk IO";
    }

    private static void retention() throws Exception {
        Path root = Files.createTempDirectory("ostt-log-bound-");
        DiagnosticLog.setEnabled(root.toFile(), true);
        DiagnosticLog.setTroubleshootingEnabled(root.toFile(), true, DiagnosticLog.TROUBLESHOOTING_DAY_MS);
        String oldLine = "1000 timing elapsed_ms=1\n";
        String old = oldLine.repeat((int) (DiagnosticLog.MAX_BYTES / oldLine.length()));
        Files.writeString(root.resolve("diagnostic.log"), old, StandardCharsets.UTF_8);
        DiagnosticLog.event(root.toFile(), "callback_delivery", "process=service,reason=callback_failed");
        assert DiagnosticLog.awaitIdleForTest(5000);
        byte[] snapshot = DiagnosticLog.snapshot(root.toFile());
        String retained = new String(snapshot, StandardCharsets.UTF_8);
        assert snapshot.length < old.length() && snapshot.length <= DiagnosticLog.MAX_BYTES;
        assert retained.startsWith(oldLine) && retained.endsWith("reason=callback_failed\n");
        for (String line : retained.split("\n")) {
            assert line.matches("[0-9]+ (timing elapsed_ms=1|callback_delivery process=service,reason=callback_failed)")
                    : "Compaction must retain only whole records";
        }
        DiagnosticLog.event(root.toFile(), "callback_delivery", "process=service,reason=secret");
        assert DiagnosticLog.awaitIdleForTest(5000);
        assert new String(DiagnosticLog.snapshot(root.toFile()), StandardCharsets.UTF_8).equals(retained);
        DiagnosticLog.setTroubleshootingEnabled(root.toFile(), false);
        DiagnosticLog.event(root.toFile(), "timing", "elapsed_ms=2");
        assert DiagnosticLog.awaitIdleForTest(5000);
        byte[] basic = DiagnosticLog.snapshot(root.toFile());
        assert basic.length <= DiagnosticLog.BASIC_MAX_BYTES
                : "Basic writes must restore the original smaller retention after consent ends";
        assert new String(basic, StandardCharsets.UTF_8).endsWith("timing elapsed_ms=2\n");
    }
}
