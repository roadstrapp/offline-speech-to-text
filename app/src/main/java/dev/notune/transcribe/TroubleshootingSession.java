package dev.notune.transcribe;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.security.SecureRandom;

/** Content-free session correlation and low-rate resource snapshots. Never owns recognition. */
final class TroubleshootingSession {
    private static final long SAMPLE_INTERVAL_MS = 15_000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Context context;
    private final File filesDir;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long id = Math.floorMod(RANDOM.nextLong(), 999_999_999_999L);
    private final String process;
    private final String route;
    private final long started = SystemClock.elapsedRealtime();
    private final long startedCpu = Process.getElapsedCpuTime();
    private volatile boolean ended;
    private final Runnable sample = new Runnable() {
        @Override public void run() {
            if (ended || !DiagnosticLog.isTroubleshootingEnabled(filesDir)) return;
            resourceSnapshot();
            handler.postDelayed(this, SAMPLE_INTERVAL_MS);
        }
    };

    private TroubleshootingSession(Context context, String process, String route) {
        this.context = context.getApplicationContext();
        this.filesDir = context.getFilesDir();
        this.process = process;
        this.route = route;
        event("capture", "start");
        sample.run();
    }

    static TroubleshootingSession start(Context context, String process, String route) {
        return DiagnosticLog.isTroubleshootingEnabled(context.getFilesDir())
                ? new TroubleshootingSession(context, process, route) : null;
    }

    long id() { return ended ? 0L : id; }

    void event(String phase, String reason) {
        if (ended) return;
        DiagnosticLog.troubleshootingEvent(filesDir, "session", prefix()
                + ",phase=" + phase + ",reason=" + reason
                + ",elapsed_ms=" + Math.max(0, SystemClock.elapsedRealtime() - started));
    }

    void pipeline(long prepareMs, long decodeMs, long postprocessMs, long stopWaitMs, int windows, int family, int warm) {
        if (ended) return;
        String[] families = {"unknown", "whisper", "parakeet", "moonshine", "gigaam", "medasr"};
        DiagnosticLog.troubleshootingEvent(filesDir, "pipeline", prefix()
                + ",prepare_ms=" + Math.max(0, prepareMs)
                + ",decode_ms=" + Math.max(0, decodeMs)
                + ",postprocess_ms=" + Math.max(0, postprocessMs)
                + ",stop_wait_ms=" + Math.max(0, stopWaitMs)
                + ",prepared_windows=" + Math.max(0, windows)
                + ",model_family=" + (family >= 0 && family < families.length ? families[family] : "unknown")
                + ",model_warm=" + (warm == 1 ? 1 : 0));
    }

    void finish(String reason) {
        if (ended) return;
        event("delivery", reason);
        resourceSnapshot();
        ended = true;
        handler.removeCallbacks(sample);
    }

    private String prefix() {
        return "process=" + process + ",session_id=" + id + ",route=" + route
                + ",version_code=" + BuildConfig.VERSION_CODE;
    }

    private void resourceSnapshot() {
        // CPU belongs to this entire process, not necessarily this session.
        // Battery/thermal state is context, never an app-attributed energy reading.
        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            String percent = level < 0 || scale <= 0 ? "unknown"
                    : Integer.toString((int) Math.max(0, Math.min(100, level * 100L / scale)));
            String power = battery == null ? "unknown"
                    : (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0 ? "battery" : "external");
            PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            String thermal = Build.VERSION.SDK_INT >= 29 && manager != null
                    ? Integer.toString(manager.getCurrentThermalStatus()) : "unknown";
            DiagnosticLog.troubleshootingEvent(filesDir, "resource", prefix()
                    + ",cpu_ms=" + Math.max(0, Process.getElapsedCpuTime() - startedCpu)
                    + ",battery_pct=" + percent + ",power=" + power + ",thermal=" + thermal);
        } catch (RuntimeException ignored) {
            // Troubleshooting must never block recording or result delivery.
        }
    }
}
