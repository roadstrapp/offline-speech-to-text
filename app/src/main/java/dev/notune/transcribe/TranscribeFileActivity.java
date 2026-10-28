package dev.notune.transcribe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

/**
 * Transcribes one audio file the user shared or opened with this app, and is the base of
 * {@link TranscribeAudioActivity}, which returns the transcript to the calling app instead.
 *
 * <p>The transcript stays on this screen until the user copies, shares or saves it. Only the
 * share/open flow keeps the established automatic clipboard copy; the result contract
 * delivers its text to the caller alone.
 */
public class TranscribeFileActivity extends AppCompatActivity {

    private static final String TAG = "OfflineVoiceInput";

    static {
        try {
            NativeLibraries.load();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private TextView statusText;
    private ProgressBar progressBar;
    private View progressArea;
    private ScrollView resultArea;
    private TextView resultText;
    private View actionRow;
    private View[] actions;
    private boolean finished = false;
    /** UI thread. The job ended without a transcript; its message stays on screen. */
    private boolean failed = false;
    private volatile boolean cancelled = false;
    private FileTranscription job;
    private volatile String audioDisplayName;
    private ActivityResultLauncher<String> saveDocument;

    /** True for the activity-result contract; the share/open flow returns nothing. */
    protected boolean returnsResult() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.transcribe_file_activity);

        statusText = findViewById(R.id.txt_status);
        progressBar = findViewById(R.id.progress_bar);
        progressArea = findViewById(R.id.progress_area);
        resultArea = findViewById(R.id.result_area);
        resultText = findViewById(R.id.txt_result);
        actionRow = findViewById(R.id.result_actions);
        actions = new View[] {
                findViewById(R.id.btn_copy), findViewById(R.id.btn_share), findViewById(R.id.btn_save)};

        saveDocument = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/plain"), this::saveTo);

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_copy).setOnClickListener(v -> {
            String text = resultText.getText().toString();
            if (!text.isEmpty()) {
                copyToClipboard(text);
                Toast.makeText(this, R.string.file_copied, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btn_share).setOnClickListener(v -> shareText());
        findViewById(R.id.btn_save).setOnClickListener(v -> {
            if (resultText.length() == 0) return;
            try {
                saveDocument.launch(FileTranscriptionContract.transcriptFileName(audioDisplayName));
            } catch (RuntimeException e) {
                Toast.makeText(this, R.string.file_save_failed, Toast.LENGTH_SHORT).show();
            }
        });

        if (returnsResult()) setResult(Activity.RESULT_CANCELED);

        Uri audioUri = getAudioUri();
        if (audioUri == null) {
            fail(FileTranscriptionContract.Failure.CLIENT, getString(R.string.file_no_audio));
            return;
        }

        if (!SpeechModelInventory.hasUsableModel(getFilesDir(), BuildConfig.HAS_BUNDLED_MODEL)) {
            fail(FileTranscriptionContract.Failure.NO_MODEL, getString(R.string.no_model_short));
            return;
        }

        Uri outputUri = returnsResult() ? outputUri() : null;
        if (returnsResult() && !acceptResultRequest(audioUri, outputUri)) {
            fail(FileTranscriptionContract.Failure.CLIENT, getString(R.string.file_no_audio));
            return;
        }

        statusText.setText(R.string.models_loading);
        String sharedType = getIntent().getType();
        Thread opener = new Thread(() -> open(audioUri, sharedType, outputUri), "transcribe-file-open");
        opener.start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Another file was shared while this screen is open (singleTask):
        // start over with it instead of silently ignoring it.
        setIntent(intent);
        recreate();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Stops the decoder thread at the next block and the native side
        // before its next model run.
        cancelled = true;
        FileTranscription running = job;
        job = null;
        if (running != null) running.cancel();
    }

    @SuppressWarnings("deprecation")
    private Uri getAudioUri() {
        Intent intent = getIntent();
        if (intent == null) return null;

        String action = intent.getAction();
        try {
            if (Intent.ACTION_SEND.equals(action)) {
                Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                return stream instanceof Uri ? (Uri) stream : null;
            } else if (Intent.ACTION_VIEW.equals(action)
                    || FileTranscriptionContract.ACTION_TRANSCRIBE_AUDIO.equals(action)) {
                return intent.getData();
            }
        } catch (RuntimeException malformedExtras) {
            return null;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private Uri outputUri() {
        try {
            Object value = getIntent().getParcelableExtra(FileTranscriptionContract.EXTRA_OUTPUT_URI);
            return value instanceof Uri ? (Uri) value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The result contract reads and writes only Uris another app granted to it. */
    private boolean acceptResultRequest(Uri audio, Uri output) {
        boolean outputRequested;
        try {
            outputRequested = getIntent().hasExtra(FileTranscriptionContract.EXTRA_OUTPUT_URI);
        } catch (RuntimeException e) {
            return false;
        }
        boolean callerIsSelf = getPackageName().equals(getCallingPackage());
        return FileTranscriptionContract.acceptRequest(
                FileTranscriptionAccess.scheme(audio), FileTranscriptionAccess.isOwnProvider(this, audio),
                FileTranscriptionAccess.scheme(output), FileTranscriptionAccess.isOwnProvider(this, output),
                outputRequested && output == null, false, callerIsSelf);
    }

    /** Worker thread: provider calls may block on another process. */
    private void open(Uri audio, String sharedType, Uri outputUri) {
        String name = FileTranscriptionAccess.displayName(this, audio);
        audioDisplayName = name;
        if (!returnsResult()) {
            String type = sharedType != null ? sharedType : FileTranscriptionAccess.type(this, audio);
            if (!FileTranscriptionContract.acceptSharedType(type, name)) {
                runOnUiThread(() -> fail(FileTranscriptionContract.Failure.UNDECODABLE,
                        getString(R.string.file_not_audio)));
                return;
            }
        }
        if (!FileTranscriptionAccess.canRead(this, audio)) {
            runOnUiThread(() -> fail(FileTranscriptionContract.Failure.CLIENT,
                    getString(R.string.file_decode_error)));
            return;
        }
        ParcelFileDescriptor output = null;
        if (outputUri != null) {
            // Fail before a long decode when the destination cannot be written.
            output = FileTranscriptionAccess.openForWrite(this, outputUri);
            if (output == null) {
                runOnUiThread(() -> fail(FileTranscriptionContract.Failure.CLIENT,
                        getString(R.string.file_save_failed)));
                return;
            }
        }
        ParcelFileDescriptor destination = output;
        runOnUiThread(() -> {
            if (cancelled || finished || failed || isFinishing()) {
                closeQuietly(destination);
                return;
            }
            job = new FileTranscription(this, audio, true, new JobListener(destination));
            job.start();
        });
    }

    private final class JobListener implements FileTranscription.Listener {
        private ParcelFileDescriptor output;

        JobListener(ParcelFileDescriptor output) {
            this.output = output;
        }

        private synchronized ParcelFileDescriptor takeOutput() {
            ParcelFileDescriptor taken = output;
            output = null;
            return taken;
        }

        @Override public void onStatus(String status) {
            onStatusUpdate(status);
        }
        @Override public void onReady() {
            runOnUiThread(() -> {
                if (!cancelled && !finished && !failed) statusText.setText(R.string.file_decoding);
            });
        }
        @Override public void onPartial(String text) {
            onPartialText(text);
        }
        @Override public void onProgress(long decodedSamples, long durationUs) {
            postProgress(decodedSamples, durationUs);
        }
        @Override public void onDecodeComplete() {
            runOnUiThread(() -> {
                if (!cancelled && !finished && !failed) statusText.setText(R.string.file_transcribing);
            });
        }
        @Override public void onResult(String text, long audioMs, long computeMs) {
            ParcelFileDescriptor destination = takeOutput();
            if (destination != null && !FileTranscriptionAccess.write(destination, text)) {
                runOnUiThread(() -> fail(FileTranscriptionContract.Failure.CLIENT,
                        getString(R.string.file_save_failed)));
                return;
            }
            onTextTranscribed(text);
        }
        @Override public void onFailure(FileTranscriptionContract.Failure failure) {
            closeQuietly(takeOutput());
            String message;
            switch (failure) {
                case NO_SPEECH: message = getString(R.string.file_no_speech); break;
                case NO_MODEL: message = getString(R.string.no_model_short); break;
                case ENGINE: message = "Error: file transcription failed"; break;
                default: message = getString(R.string.file_decode_error); break;
            }
            runOnUiThread(() -> fail(failure, message));
        }
    }

    // Transcript of the windows completed so far (also driven by the device smoke test).
    public void onPartialText(String text) {
        runOnUiThread(() -> {
            if (cancelled) return;
            if (finished) return;
            resultArea.setVisibility(View.VISIBLE);
            resultText.setText(text);
        });
    }

    /** Displays a native status; an error status ends this screen's job. */
    public void onStatusUpdate(String status) {
        runOnUiThread(() -> {
            if (cancelled) return;
            if (NativeStatus.isError(status)) {
                fail(FileTranscriptionContract.Failure.ENGINE, status);
            } else if (!finished && !failed && status != null) {
                statusText.setText(status);
            }
        });
    }

    private void onTextTranscribed(String text) {
        runOnUiThread(() -> {
            if (cancelled || finished || failed) return;
            finished = true;
            if (returnsResult()) {
                // The calling app receives the text; nothing is copied to the shared clipboard.
                ArrayList<String> results = new ArrayList<>();
                results.add(text);
                Intent data = new Intent()
                        .putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results)
                        .putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[] {-1.0f});
                setResult(Activity.RESULT_OK, data);
                finish();
                return;
            }
            // Hide progress, show result
            progressArea.setVisibility(View.GONE);
            resultArea.setVisibility(View.VISIBLE);
            showActions();

            resultText.setText(text);

            // Auto-copy to clipboard
            copyToClipboard(text);
            Toast.makeText(this, R.string.file_copied_auto, Toast.LENGTH_LONG).show();
        });
    }

    /** Shows how far transcription got, against the file duration when known. */
    private void postProgress(long decodedSamples, long durationUs) {
        long doneSec = decodedSamples / AudioFileDecoder.TARGET_RATE;
        String msg = durationUs > 0
                ? getString(R.string.file_transcribing_progress,
                        formatTime(doneSec), formatTime(durationUs / 1_000_000))
                : getString(R.string.file_transcribing_at, formatTime(doneSec));
        runOnUiThread(() -> {
            if (cancelled) return;
            if (!finished && !failed) statusText.setText(msg);
        });
    }

    private static String formatTime(long seconds) {
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** UI thread. Ends this screen's job; recovered partial text stays available. */
    private void fail(FileTranscriptionContract.Failure failure, String message) {
        if (cancelled) return;
        failed = true;
        FileTranscription running = job;
        job = null;
        if (running != null) running.cancel();
        if (returnsResult()) setResult(FileTranscriptionContract.activityResult(failure));
        statusText.setText(message);
        progressBar.setVisibility(View.GONE);
        if (resultText.length() > 0) showActions();
    }

    private void showActions() {
        actionRow.setVisibility(View.VISIBLE);
        for (View action : actions) action.setVisibility(View.VISIBLE);
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Transcription", text);
        clipboard.setPrimaryClip(clip);
    }

    /** Explicit user action: hands the transcript to the app the user picks. */
    private void shareText() {
        String text = resultText.getText().toString();
        if (text.isEmpty()) return;
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.file_share_title)));
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.file_share_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** Writes the visible transcript to the document the user created. */
    private void saveTo(Uri document) {
        if (document == null) return;
        String text = resultText.getText().toString();
        if (text.isEmpty()) return;
        new Thread(() -> {
            boolean saved = FileTranscriptionAccess.write(this, document, text);
            runOnUiThread(() -> Toast.makeText(this,
                    saved ? R.string.file_saved : R.string.file_save_failed,
                    Toast.LENGTH_SHORT).show());
        }, "transcribe-file-save").start();
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try { descriptor.close(); } catch (java.io.IOException ignored) { }
    }
}
