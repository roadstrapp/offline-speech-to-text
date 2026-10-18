package dev.notune.transcribe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.content.pm.PackageManager;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class RecognizeActivity extends AppCompatActivity {

    private static final String TAG = "OfflineVoiceInput";

    static {
        try {
            NativeLibraries.load();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private TextView status;
    private boolean isRecording = false;
    private boolean awaitingResult = false;
    private MicLevelView micLevel;
    private View processingRing;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private boolean pauseAudioActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int panelPosition = RecognizePanelPrefs.get(this);
        if (panelPosition == RecognizePanelPrefs.BOTTOM) {
            setContentView(R.layout.recognize_activity);
        } else {
            setContentView(R.layout.recognize_activity_side);
            if (panelPosition == RecognizePanelPrefs.LEFT) {
                View panel = findViewById(R.id.panel);
                FrameLayout.LayoutParams params =
                        (FrameLayout.LayoutParams) panel.getLayoutParams();
                params.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
                panel.setLayoutParams(params);
            }
        }

        micLevel = findViewById(R.id.mic_level);
        processingRing = findViewById(R.id.mic_processing_ring);
        status = findViewById(R.id.txt_status);

        findViewById(R.id.btn_close).setOnClickListener(v -> {
            // discard current recording
            awaitingResult = false;
            if (isRecording) {
                isRecording = false;
                cancelRecording();   // new native method
            }
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        // Tap anywhere (or on mic) to stop
        findViewById(R.id.root).setOnClickListener(v -> finishRecording());

        // Permission check
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            status.setText(R.string.voice_need_mic);
            return;
        }

        initNative(this);
        isRecording = true;
        status.setText(R.string.voice_listening);
        if (isPauseAudioEnabled()) {
            audioPauser.request(this);
            pauseAudioActive = true;
        }
        startRecording(isAutoStopEnabled(), AutoStopDelay.get(getFilesDir()));
    }

    /** Stop capture and transcribe — used by both tap-to-stop and auto-stop. */
    private void finishRecording() {
        if (!isRecording) return;
        isRecording = false;
        awaitingResult = true;
        status.setText(R.string.voice_processing);
        showProcessing(true);
        stopRecording();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    // Called from Rust (monitor thread) when trailing silence is detected.
    public void onAutoStop() {
        runOnUiThread(this::finishRecording);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // The popup is no longer visible (user switched apps or went home).
        // It runs in its own task (singleTask), so it would otherwise keep
        // recording invisibly in the background and never reappear. Discard
        // and close so the next mic tap starts fresh. (Background recording
        // is a keyboard-only feature; a popup must not record unseen.)
        if (isRecording && !isFinishing()) {
            isRecording = false;
            try { cancelRecording(); } catch (Throwable t) { /* ignore */ }
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            setResult(Activity.RESULT_CANCELED);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        try { cleanupNative(); } catch (Throwable t) { /* ignore */ }
    }

    // Called from Rust
    public void onStatusUpdate(String s) {
        runOnUiThread(() -> {
            boolean error = s != null && s.startsWith("Error");
            if (error) awaitingResult = false;
            final String shown;
            if (awaitingResult && !error) {
                // A late model-ready callback must not make an in-flight
                // transcription look finished while its result is pending.
                shown = "Processing...";
            } else if ("Ready".equals(s)) {
                // The model-ready status can arrive after recording started.
                shown = isRecording ? "Listening... (Tap to stop)" : "Ready";
            } else if ("Listening...".equals(s)) {
                shown = "Listening... (Tap to stop)";
            } else {
                shown = s;
            }
            showProcessing(awaitingResult || isProcessingStatus(s));
            status.setText(shown);
        });
    }

    /** Content-free timing emitted only when the user opted into diagnostics. */
    public void onTranscriptionMetrics(long audioMs, long computeMs, int threads) {
        DiagnosticLog.event(getFilesDir(), "timing",
                "process=app,audio_ms=" + Math.max(0L, audioMs)
                        + ",duration_ms=" + Math.max(0L, computeMs)
                        + ",cpu_threads=" + Math.max(0, threads));
    }

    // Called from Rust with 0..1
    public void onAudioLevel(float level) {
        runOnUiThread(() -> micLevel.setLevel(level));
    }

    // Called from Rust – keep same method name as IME for code reuse
    public void onTextTranscribed(String text) {
        runOnUiThread(() -> {
            awaitingResult = false;
            showProcessing(false);
            if (text == null || text.trim().isEmpty()) {
                // Give visible feedback while returning Android's empty-result contract.
                android.widget.Toast.makeText(this, getString(R.string.voice_no_speech), android.widget.Toast.LENGTH_SHORT).show();
                setResult(Activity.RESULT_CANCELED);
                finish();
                return;
            }

            ArrayList<String> results = new ArrayList<>();
            results.add(text);

            Intent data = new Intent();
            data.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
            data.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[] { -1.0f });

            setResult(Activity.RESULT_OK, data);
            finish();
        });
    }

    private void showProcessing(boolean processing) {
        if (processingRing != null) {
            processingRing.setVisibility(processing ? View.VISIBLE : View.GONE);
        }
        if (processing && micLevel != null) micLevel.setLevel(0f);
    }

    private static boolean isProcessingStatus(String status) {
        return status != null && (status.contains("Transcribing")
                || status.contains("Processing") || status.contains("Waiting"));
    }

    private boolean isPauseAudioEnabled() {
        return new java.io.File(getFilesDir(), "pause_audio").exists();
    }

    /** Opt-in via the "Auto-stop after silence" setting (default off). */
    private boolean isAutoStopEnabled() {
        return new java.io.File(getFilesDir(), "auto_stop").exists();
    }

    // Native methods
    private native void initNative(RecognizeActivity activity);
    private native void cleanupNative();
    private native void startRecording(boolean autoStop, int trailingPauseMillis);
    private native void stopRecording();
    private native void cancelRecording();
}
