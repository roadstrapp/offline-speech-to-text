package dev.notune.transcribe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** App-private transcript review. The transcript never crosses an internal Intent extra. */
public final class ForegroundResultActivity extends Activity {
    private String token;
    private long generation;
    private String nonce;
    private TextView text;
    private Button share;
    private Button clear;
    private long publicationWaitDeadline;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        token = getIntent().getStringExtra(ForegroundRecordingService.EXTRA_TOKEN);
        generation = getIntent().getLongExtra(ForegroundRecordingService.EXTRA_GENERATION, -1);
        nonce = getIntent().getStringExtra(ForegroundRecordingService.EXTRA_NONCE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);
        text = new TextView(this);
        share = new Button(this);
        share.setText("Share and clear result");
        share.setOnClickListener(v -> shareAndClear());
        clear = new Button(this);
        clear.setText("Delete result");
        clear.setOnClickListener(v -> deleteAndFinish());
        root.addView(text);
        root.addView(share);
        root.addView(clear);
        setContentView(root);

        TransientResultVault.Entry entry = TransientResultVault.global()
                .read(token, generation, nonce);
        if (entry != null) {
            show(entry);
        } else if (TransientResultVault.global().isStaged(token, generation, nonce)) {
            text.setText("Finishing transcription…");
            share.setEnabled(false);
            clear.setEnabled(false);
            publicationWaitDeadline = SystemClock.elapsedRealtime() + 750L;
            text.postDelayed(this::finishPublicationLoad, 50L);
        } else {
            showExpired();
        }
    }

    private void finishPublicationLoad() {
        TransientResultVault.Entry entry = TransientResultVault.global()
                .read(token, generation, nonce);
        if (entry != null) {
            show(entry);
        } else if (TransientResultVault.global().isStaged(token, generation, nonce)
                && SystemClock.elapsedRealtime() < publicationWaitDeadline) {
            text.postDelayed(this::finishPublicationLoad, 50L);
        } else {
            showExpired();
        }
    }

    private void show(TransientResultVault.Entry entry) {
        ResultNotification.cancel(this, generation);
        text.setText(entry.text);
        share.setEnabled(true);
        clear.setEnabled(true);
        text.postDelayed(() -> {
            if (TransientResultVault.global().read(token, generation, nonce) == null) {
                showExpired();
            }
        }, Math.max(1L, entry.expiresAtMillis - System.currentTimeMillis()));
    }

    private void showExpired() {
        ResultNotification.cancel(this, generation);
        text.setText("This result expired or was deleted.");
        share.setEnabled(false);
        clear.setEnabled(false);
    }

    private void shareAndClear() {
        TransientResultVault.Entry current = TransientResultVault.global()
                .read(token, generation, nonce);
        if (current == null) {
            showExpired();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, current.text);
        try {
            startActivity(Intent.createChooser(send, "Share transcription"));
            TransientResultVault.global().consume(token, generation, nonce);
            finish();
        } catch (RuntimeException unavailable) {
            text.setText("No share destination is available. Your result is still private here.");
        }
    }

    private void deleteAndFinish() {
        TransientResultVault.global().clear(token, generation, nonce);
        ResultNotification.cancel(this, generation);
        finish();
    }

    @Override protected void onStop() {
        TransientResultVault.Entry pending = TransientResultVault.global()
                .read(token, generation, nonce);
        if (pending != null && ResultNotification.canPost(this)) {
            ResultNotification.post(this, pending);
        }
        super.onStop();
    }
}
