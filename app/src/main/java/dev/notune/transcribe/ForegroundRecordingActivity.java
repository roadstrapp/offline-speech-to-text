package dev.notune.transcribe;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.content.PermissionChecker;

/** Explicit app/IME launch and recovery surface for long-form recording. */
public final class ForegroundRecordingActivity extends Activity {
    private static volatile boolean resumed;
    private static volatile boolean startFailurePending;
    private TextView status;
    private Button primary;
    private Button secondary;
    private IForegroundRecordingService service;
    private boolean bound;
    private long currentGeneration = -1;

    private final IForegroundRecordingObserver observer = new IForegroundRecordingObserver.Stub() {
        @Override public void onStateChanged(int state, long generation) {
            currentGeneration = generation;
            runOnUiThread(() -> renderState(state));
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IForegroundRecordingService.Stub.asInterface(binder);
            bound = true;
            try {
                String token = getIntent().getStringExtra(ForegroundRecordingService.EXTRA_TOKEN);
                if (token != null && !service.matchesSession(
                        token,
                        getIntent().getLongExtra(ForegroundRecordingService.EXTRA_GENERATION, -1),
                        getIntent().getStringExtra(ForegroundRecordingService.EXTRA_NONCE))) {
                    finish();
                    return;
                }
                service.registerObserver(observer);
                renderState(service.getState());
            } catch (Exception ignored) {
                status.setText("Recording status is unavailable. Try again from the app.");
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ForegroundRecordingService.ensureChannel(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        status = new TextView(this);
        status.setTextSize(18);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        primary = new Button(this);
        root.addView(primary, new LinearLayout.LayoutParams(-1, -2));

        secondary = new Button(this);
        secondary.setVisibility(View.GONE);
        root.addView(secondary, new LinearLayout.LayoutParams(-1, -2));

        Button settings = new Button(this);
        settings.setText("Open notification settings");
        settings.setOnClickListener(v -> openNotificationSettings());
        root.addView(settings, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        renderPrerequisites();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        TransientResultVault.Entry existing = TransientResultVault.global().peek();
        if (existing != null) {
            renderPendingResult(existing);
            return;
        }
        renderPrerequisites();
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    static boolean isResumedForStart() {
        return resumed;
    }

    @Override protected void onStart() {
        super.onStart();
        if (TransientResultVault.global().peek() != null
                && getIntent().getStringExtra(ForegroundRecordingService.EXTRA_TOKEN) == null) {
            return;
        }
        bindService(new Intent(this, ForegroundRecordingService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        if (bound) {
            try { service.unregisterObserver(observer); } catch (Exception ignored) { }
            unbindService(connection);
            bound = false;
        }
        super.onStop();
    }

    private void startIfReady() {
        TransientResultVault.Entry pending = TransientResultVault.global().peek();
        if (pending != null) {
            renderPendingResult(pending);
            return;
        }
        String failure = prerequisiteFailure();
        if (failure != null) {
            status.setText(failure);
            return;
        }
        startFailurePending = false;
        status.setText("Preparing recording…");
        Intent command = new Intent(this, ForegroundRecordingService.class)
                .setAction(ForegroundRecordingService.ACTION_START);
        try {
            ContextCompat.startForegroundService(this, command);
        } catch (RuntimeException unavailable) {
            status.setText("Recording could not start. Keep this screen open and try again.");
        }
    }

    private void renderPrerequisites() {
        if (startFailurePending) {
            status.setText("Recording could not start. Check permissions and try again.");
            primary.setText("Try again");
            primary.setEnabled(prerequisiteFailure() == null);
            primary.setOnClickListener(v -> startIfReady());
            secondary.setVisibility(View.GONE);
            return;
        }
        String failure = prerequisiteFailure();
        status.setText(failure == null
                ? "Ready. Recording stays controlled from a private notification."
                : failure);
        primary.setText("Start recording");
        primary.setEnabled(failure == null);
        primary.setOnClickListener(v -> startIfReady());
        secondary.setVisibility(View.GONE);
    }

    private String prerequisiteFailure() {
        if (!hasVisibleMicrophonePermission(this)) {
            return "Microphone permission is required. Grant it from the main app.";
        }
        if (!hasUsableModel(this)) {
            return "A speech model is required. Import one from the main app.";
        }
        if (Build.VERSION.SDK_INT >= 33
                && PermissionChecker.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PermissionChecker.PERMISSION_GRANTED) {
            return "Notification permission is required for recording controls. Allow it in notification settings.";
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) {
            return "Notifications are disabled. Enable them before recording in other apps.";
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = nm.getNotificationChannel(ForegroundRecordingService.CHANNEL_ID);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                return "The Active recording notification channel is blocked. Enable it before recording.";
            }
        }
        return null;
    }

    /**
     * Android 10+ normally stores an in-use microphone grant as MODE_FOREGROUND.
     * PermissionChecker exposes that raw app-op as denied, so re-check the raw
     * mode and accept it only while this explicit launch activity is resumed.
     */
    static boolean hasVisibleMicrophonePermission(Context context) {
        boolean runtimeGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!runtimeGranted || !isResumedForStart()) return false;
        if (PermissionChecker.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PermissionChecker.PERMISSION_GRANTED) return true;
        try {
            AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
            if (appOps == null) return false;
            int mode = Build.VERSION.SDK_INT >= 29
                    ? appOps.unsafeCheckOpNoThrow(
                            AppOpsManager.OPSTR_RECORD_AUDIO,
                            Process.myUid(), context.getPackageName())
                    : appOps.checkOpNoThrow(
                            AppOpsManager.OPSTR_RECORD_AUDIO,
                            Process.myUid(), context.getPackageName());
            return MicrophonePermissionPolicy.allowsVisibleStart(
                    true, mode, isResumedForStart());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    static boolean hasUsableModel(Context context) {
        java.io.File marker = new java.io.File(context.getFilesDir(), "active_model");
        if (!marker.isFile()) return BuildConfig.HAS_BUNDLED_MODEL;
        try {
            String name = new String(java.nio.file.Files.readAllBytes(marker.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            if (name.isEmpty()) return BuildConfig.HAS_BUNDLED_MODEL;
            return new java.io.File(new java.io.File(context.getFilesDir(), "models"), name).isFile();
        } catch (java.io.IOException ignored) {
            return false;
        }
    }

    static void reportStartFailure() {
        startFailurePending = true;
    }

    private void renderState(int ordinal) {
        ForegroundRecordingCoordinator.State[] states = ForegroundRecordingCoordinator.State.values();
        if (ordinal < 0 || ordinal >= states.length) {
            renderPrerequisites();
            return;
        }
        switch (states[ordinal]) {
            case STARTING:
                status.setText("Preparing recording");
                showServiceActions("Cancel start", 1, null, 0);
                break;
            case RECORDING:
                status.setText("Recording speech");
                showServiceActions("Stop and discard", 1, "Stop and transcribe", 2);
                if (getIntent().getStringExtra(ForegroundRecordingService.EXTRA_TOKEN) == null) {
                    status.postDelayed(this::finish, 250L);
                }
                break;
            case STOPPING_FOR_TRANSCRIPTION:
                showTerminalizing("Stopping");
                break;
            case TRANSCRIBING:
                status.setText("Transcribing — microphone off");
                showServiceActions("Discard", 1, null, 0);
                break;
            case RESULT_READY:
                TransientResultVault.Entry result = TransientResultVault.global().peek();
                if (result == null) renderPrerequisites(); else renderPendingResult(result);
                break;
            case DISCARDING: showTerminalizing("Discarding recording…"); break;
            case FAILED: showTerminalizing("Recording stopped. Check permissions and try again."); break;
            default: renderPrerequisites(); break;
        }
    }

    private void showServiceActions(String firstLabel, int firstAction,
                                    String secondLabel, int secondAction) {
        primary.setText(firstLabel);
        primary.setEnabled(service != null && currentGeneration >= 0);
        primary.setOnClickListener(v -> performServiceAction(firstAction));
        if (secondLabel == null) {
            secondary.setVisibility(View.GONE);
        } else {
            secondary.setVisibility(View.VISIBLE);
            secondary.setText(secondLabel);
            secondary.setEnabled(service != null && currentGeneration >= 0);
            secondary.setOnClickListener(v -> performServiceAction(secondAction));
        }
    }

    private void performServiceAction(int action) {
        try {
            if (service == null || !service.performAction(action, currentGeneration)) {
                showTerminalizing("Recording no longer active");
            }
        } catch (Exception ignored) {
            showTerminalizing("Recording status is unavailable. Try again from the app.");
        }
    }

    private void renderPendingResult(TransientResultVault.Entry entry) {
        status.setText("A private transcription is ready.");
        primary.setText("Review result");
        primary.setEnabled(true);
        primary.setOnClickListener(v -> startActivity(new Intent(this, ForegroundResultActivity.class)
                .putExtra(ForegroundRecordingService.EXTRA_TOKEN, entry.token)
                .putExtra(ForegroundRecordingService.EXTRA_GENERATION, entry.generation)
                .putExtra(ForegroundRecordingService.EXTRA_NONCE, entry.nonce)));
        secondary.setVisibility(View.VISIBLE);
        secondary.setText("Discard result");
        secondary.setEnabled(true);
        secondary.setOnClickListener(v -> {
            if (TransientResultVault.global().clear(entry.token, entry.generation, entry.nonce)) {
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(
                        ForegroundRecordingService.resultTag(entry.generation),
                        ForegroundRecordingService.RESULT_NOTIFICATION_ID);
            }
            renderPrerequisites();
        });
    }

    private void showTerminalizing(String message) {
        status.setText(message);
        primary.setText("Stopping");
        primary.setEnabled(false);
        secondary.setVisibility(View.GONE);
    }

    private void openNotificationSettings() {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(i);
    }
}
