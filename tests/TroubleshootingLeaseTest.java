package dev.notune.transcribe;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TroubleshootingLeaseTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("ostt-diagnostic-lease-");
        File dir = directory.toFile();
        assert !DiagnosticLog.isTroubleshootingEnabled(dir);
        try {
            DiagnosticLog.setTroubleshootingEnabled(dir, true);
            throw new AssertionError("Must not enable without basic consent");
        } catch (java.io.IOException expected) { }
        DiagnosticLog.setEnabled(dir, true);
        assert !DiagnosticLog.isTroubleshootingEnabled(dir);
        DiagnosticLog.troubleshootingEvent(dir, "session", "process=ime,session_id=1,reason=start");
        assert DiagnosticLog.awaitIdleForTest(5000);
        assert DiagnosticLog.snapshot(dir).length == 0;
        DiagnosticLog.setTroubleshootingEnabled(dir, true);
        assert DiagnosticLog.isTroubleshootingEnabled(dir);
        DiagnosticLog.troubleshootingEvent(dir, "session",
                "process=ime,session_id=12,phase=capture,reason=start,elapsed_ms=0");
        long start = 1_000_000;
        long end = start + DiagnosticLog.TROUBLESHOOTING_DURATION_MS;
        assert DiagnosticLog.validTroubleshootingLease(start, end, start);
        assert DiagnosticLog.validTroubleshootingLease(start, end, end - 1);
        assert !DiagnosticLog.validTroubleshootingLease(start, end, end);
        assert !DiagnosticLog.validTroubleshootingLease(start, end, start - 1);
        assert !DiagnosticLog.validTroubleshootingLease(start, end + 1, start);
        assert !DiagnosticLog.validTroubleshootingLease(-1, end, start);
        long dayEnd = start + DiagnosticLog.TROUBLESHOOTING_DAY_MS;
        assert DiagnosticLog.validTroubleshootingLease(start, dayEnd, end + 1);
        assert DiagnosticLog.validTroubleshootingLease(start, dayEnd, dayEnd - 1);
        assert !DiagnosticLog.validTroubleshootingLease(start, dayEnd, dayEnd);
        assert !DiagnosticLog.validTroubleshootingLease(start, dayEnd + 1, start);
        try {
            DiagnosticLog.setTroubleshootingEnabled(dir, true, 2 * DiagnosticLog.TROUBLESHOOTING_DAY_MS);
            throw new AssertionError("Arbitrary consent durations must be rejected");
        } catch (IllegalArgumentException expected) { }
        DiagnosticLog.setTroubleshootingEnabled(dir, true, DiagnosticLog.TROUBLESHOOTING_DAY_MS);
        byte[] dayLease = Files.readAllBytes(directory.resolve("diagnostic_troubleshooting"));
        ByteBuffer day = ByteBuffer.wrap(dayLease);
        assert day.getLong(8) - day.getLong(0) == DiagnosticLog.TROUBLESHOOTING_DAY_MS;
        assert DiagnosticLog.isTroubleshootingEnabled(dir);
        DiagnosticLog.troubleshootingEvent(dir, "resource",
                "process=ime,session_id=12,cpu_ms=34,battery_pct=99,power=battery,thermal=0");
        DiagnosticLog.troubleshootingEvent(dir, "pipeline",
                "process=ime,prepare_ms=1,decode_ms=2,postprocess_ms=3,stop_wait_ms=4,prepared_windows=2");
        DiagnosticLog.troubleshootingEvent(dir, "window_plan",
                "process=service,session_id=12,raw_window_limit_ms=59500,planned_windows=1");
        DiagnosticLog.troubleshootingEvent(dir, "window_plan",
                "planned_windows=private_transcript");
        for (String forbidden : new String[]{"model=secret", "route=secret", "reason=secret", "phase=secret",
                "transcript=secret", "audio=secret", "editor=secret", "dictionary=secret", "thermal=8"}) {
            DiagnosticLog.troubleshootingEvent(dir, "session", forbidden);
        }
        assert DiagnosticLog.awaitIdleForTest(5000);
        String log = new String(DiagnosticLog.snapshot(dir), StandardCharsets.UTF_8);
        assert log.contains("cpu_ms=34") && log.contains("prepared_windows=2");
        assert log.contains("raw_window_limit_ms=59500,planned_windows=1");
        assert !log.contains("private_transcript");
        assert log.contains("phase=capture,reason=start,elapsed_ms=0");
        assert !log.contains("secret") && !log.contains("thermal=8");
        DiagnosticLog.setTroubleshootingEnabled(dir, false);
        assert !DiagnosticLog.isTroubleshootingEnabled(dir);
        DiagnosticLog.troubleshootingEvent(dir, "session", "session_id=987,phase=capture,reason=start,elapsed_ms=0");
        DiagnosticLog.troubleshootingEvent(dir, "window_plan", "session_id=987,planned_windows=1");
        assert DiagnosticLog.awaitIdleForTest(5000);
        assert !new String(DiagnosticLog.snapshot(dir), StandardCharsets.UTF_8).contains("session_id=987");
        Path lease = directory.resolve("diagnostic_troubleshooting");
        Files.write(lease, new byte[17]);
        assert !DiagnosticLog.isTroubleshootingEnabled(dir);
        Files.write(lease, ByteBuffer.allocate(16).putLong(start).putLong(end).array());
        assert !DiagnosticLog.isTroubleshootingEnabled(dir);
        assert !Files.exists(lease) : "Expired consent must not revive after a clock change";
        DiagnosticLog.setTroubleshootingEnabled(dir, true, DiagnosticLog.TROUBLESHOOTING_FOREVER_MS);
        assert DiagnosticLog.isTroubleshootingEnabled(dir);
        assert ByteBuffer.wrap(Files.readAllBytes(lease)).getLong(8) == Long.MAX_VALUE;
        DiagnosticLog.setTroubleshootingEnabled(dir, false);
        assert !DiagnosticLog.isTroubleshootingEnabled(dir);
        DiagnosticLog.setTroubleshootingEnabled(dir, true, DiagnosticLog.TROUBLESHOOTING_FOREVER_MS);
        DiagnosticLog.setEnabled(dir, false);
        assert !Files.exists(lease);
        DiagnosticLog.setEnabled(dir, true);
        assert !DiagnosticLog.isTroubleshootingEnabled(dir);
        DiagnosticLog.clear(dir);
        assert DiagnosticLog.snapshot(dir).length == 0;
        System.out.println("Troubleshooting consent/expiry/privacy tests passed");
    }
}
