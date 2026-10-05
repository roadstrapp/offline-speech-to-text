package dev.notune.transcribe;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CompoundButton;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import java.io.IOException;

/** Owns explicit warning consent and the visible, expiring troubleshooting state. */
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
            if (!enabled) { setEnabled(false); return; }
            refresh(); // A switch gesture is not warning consent.
            warning = new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.troubleshooting_warning_title)
                    .setMessage(R.string.troubleshooting_warning)
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> refresh())
                    .setPositiveButton(R.string.troubleshooting_enable, (dialog, which) -> setEnabled(true))
                    .setOnCancelListener(dialog -> refresh())
                    .show();
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

    private void setEnabled(boolean enabled) {
        try {
            DiagnosticLog.setTroubleshootingEnabled(activity.getFilesDir(), enabled);
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
