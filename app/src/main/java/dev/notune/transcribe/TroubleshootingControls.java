package dev.notune.transcribe;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CompoundButton;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import java.io.IOException;

/** Owns explicit warning consent and the visible troubleshooting state. */
final class TroubleshootingControls {
    private final AppCompatActivity activity;
    private final CompoundButton toggle;
    private final View banner;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean binding;
    private androidx.appcompat.app.AlertDialog warning;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refresh();
            if (DiagnosticLog.isTroubleshootingEnabled(activity.getFilesDir())) {
                handler.postDelayed(this, 10_000L);
            }
        }
    };

    TroubleshootingControls(AppCompatActivity activity) {
        this.activity = activity;
        toggle = activity.findViewById(R.id.switch_troubleshooting);
        banner = activity.findViewById(R.id.troubleshooting_banner);
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            if (binding) return;
            if (!enabled) { setEnabled(false, DiagnosticLog.TROUBLESHOOTING_DURATION_MS); return; }
            refresh(); // A switch gesture is not warning consent.
            // A vertical list keeps all four choices in the same reading order on every layout.
            CharSequence[] choices = {
                    activity.getString(R.string.troubleshooting_enable),
                    activity.getString(R.string.troubleshooting_enable_day),
                    activity.getString(R.string.troubleshooting_enable_forever),
                    activity.getString(android.R.string.cancel)
            };
            warning = new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.troubleshooting_warning_title)
                    .setItems(choices, (dialog, which) -> {
                        int choice = which - 1; // The non-selectable warning header occupies position zero.
                        if (choice < 0 || choice >= 3) { refresh(); return; }
                        long[] durations = {DiagnosticLog.TROUBLESHOOTING_DURATION_MS,
                                DiagnosticLog.TROUBLESHOOTING_DAY_MS,
                                DiagnosticLog.TROUBLESHOOTING_FOREVER_MS};
                        setEnabled(true, durations[choice]);
                    })
                    .setOnCancelListener(dialog -> refresh())
                    .create();
            // Use a separate header so the warning and the ordered list are both visible.
            android.widget.TextView explanation = new android.widget.TextView(activity);
            explanation.setText(R.string.troubleshooting_warning);
            int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
            explanation.setPadding(padding, padding / 2, padding, padding / 2);
            warning.show();
            warning.getListView().addHeaderView(explanation, null, false);
        });
        refresh();
    }

    void refresh() {
        binding = true;
        boolean enabled = DiagnosticLog.isTroubleshootingEnabled(activity.getFilesDir());
        toggle.setEnabled(DiagnosticLog.isEnabled(activity.getFilesDir()));
        toggle.setChecked(enabled);
        banner.setVisibility(enabled ? View.VISIBLE : View.GONE);
        binding = false;
    }

    private void setEnabled(boolean enabled, long durationMs) {
        try {
            DiagnosticLog.setTroubleshootingEnabled(activity.getFilesDir(), enabled, durationMs);
        } catch (IOException | RuntimeException failure) {
            Snackbar.make(banner, R.string.diagnostic_log_error, Snackbar.LENGTH_LONG).show();
        }
        resume();
    }

    void resume() {
        handler.removeCallbacks(refresh);
        refresh.run();
    }

    void pause() { handler.removeCallbacks(refresh); }

    void destroy() {
        pause();
        if (warning != null) {
            warning.dismiss();
            warning = null;
        }
    }
}
