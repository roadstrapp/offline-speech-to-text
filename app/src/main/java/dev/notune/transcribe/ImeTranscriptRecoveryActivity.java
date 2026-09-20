package dev.notune.transcribe;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Private recovery surface used only when a voice-keyboard insert is uncertain. */
public final class ImeTranscriptRecoveryActivity extends AppCompatActivity {
    static final String EXTRA_ALREADY_INSERTED = "already_inserted";
    private TextView transcript;
    private Button copy;
    private Button keep;
    private Button delete;
    private final ExecutorService recoveryIo = Executors.newSingleThreadExecutor();
    private String displayedText;
    private int loadGeneration;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_ime_transcript_recovery);
        TextView heading = findViewById(R.id.ime_recovery_heading);
        ViewCompat.setAccessibilityHeading(heading, true);
        TextView explanation = findViewById(R.id.ime_recovery_explanation);
        if (getIntent().getBooleanExtra(EXTRA_ALREADY_INSERTED, false)) {
            explanation.setText(R.string.ime_recovery_explanation_inserted);
        }
        transcript = findViewById(R.id.ime_recovery_text);
        copy = findViewById(R.id.ime_recovery_copy);
        keep = findViewById(R.id.ime_recovery_keep);
        delete = findViewById(R.id.ime_recovery_delete);
        copy.setOnClickListener(v -> copyAndClear());
        keep.setOnClickListener(v -> finish());
        delete.setOnClickListener(v -> confirmDelete());
    }

    @Override protected void onResume() {
        super.onResume();
        loadRecord();
    }

    private void loadRecord() {
        int generation = ++loadGeneration;
        displayedText = null;
        transcript.setText(R.string.ime_recovery_loading);
        copy.setEnabled(false);
        keep.setVisibility(View.VISIBLE);
        delete.setVisibility(View.GONE);
        recoveryIo.execute(() -> {
            PendingTranscriptRecord.Entry pending = PendingImeTranscriptStore.read(this);
            String pendingText = pending == null ? EmergencyTranscriptBuffer.peek() : pending.text;
            boolean hasRecord = pending == null && PendingImeTranscriptStore.hasRecordFast(this);
            runOnUiThread(() -> renderLoaded(generation, pendingText, hasRecord));
        });
    }

    private void renderLoaded(int generation, String pendingText, boolean damaged) {
        if (generation != loadGeneration || isFinishing() || isDestroyed()) return;
        displayedText = pendingText;
        if (pendingText == null) {
            transcript.setText(damaged
                    ? R.string.ime_recovery_damaged : R.string.ime_recovery_unavailable);
            copy.setEnabled(false);
            keep.setVisibility(View.GONE);
            delete.setVisibility(damaged ? View.VISIBLE : View.GONE);
            return;
        }
        transcript.setText(pendingText);
        copy.setEnabled(true);
        keep.setVisibility(View.VISIBLE);
        delete.setVisibility(View.VISIBLE);
    }

    private void copyAndClear() {
        String pendingText = displayedText;
        if (pendingText == null) {
            loadRecord();
            return;
        }
        try {
            if (!TranscriptClipboard.copy(this, pendingText)) {
                Toast.makeText(this, R.string.ime_recovery_copy_failed, Toast.LENGTH_LONG).show();
                return;
            }
            copy.setEnabled(false);
            recoveryIo.execute(() -> {
                boolean cleared = PendingImeTranscriptStore.clear(this);
                runOnUiThread(() -> {
                    if (cleared) {
                        EmergencyTranscriptBuffer.clear();
                        Toast.makeText(this, R.string.ime_recovery_copied,
                                Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        copy.setEnabled(true);
                        Toast.makeText(this, R.string.ime_recovery_clear_failed,
                                Toast.LENGTH_LONG).show();
                    }
                });
            });
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, R.string.ime_recovery_copy_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ime_recovery_delete_title)
                .setMessage(R.string.ime_recovery_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ime_recovery_delete, (dialog, which) -> {
                    copy.setEnabled(false);
                    delete.setEnabled(false);
                    recoveryIo.execute(() -> {
                        boolean cleared = PendingImeTranscriptStore.clear(this);
                        runOnUiThread(() -> {
                            if (cleared) {
                                EmergencyTranscriptBuffer.clear();
                                finish();
                            } else {
                                copy.setEnabled(displayedText != null);
                                delete.setEnabled(true);
                                Toast.makeText(this, R.string.ime_recovery_clear_failed,
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    });
                })
                .show();
    }

    @Override protected void onDestroy() {
        recoveryIo.shutdownNow();
        super.onDestroy();
    }
}
