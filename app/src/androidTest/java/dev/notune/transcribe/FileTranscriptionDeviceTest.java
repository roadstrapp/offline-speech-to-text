package dev.notune.transcribe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

/** Real shared-file entry, cancellation, stale-owner cleanup and native decoding. */
final class FileTranscriptionDeviceTest {
    static void run(Instrumentation test) throws Exception {
        File directory = new File(test.getTargetContext().getCacheDir(), "diagnostics");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new AssertionError("file fixture directory");
        File audio = new File(directory, "public-file-validation.wav");
        Activity first = null, second = null;
        try {
            try (InputStream input = test.getTargetContext().getAssets().open("bench.wav")) {
                Files.copy(input, audio.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Uri uri = new Uri.Builder().scheme("content")
                    .authority(test.getTargetContext().getPackageName() + ".fileprovider")
                    .appendPath("diagnostic_exports").appendPath(audio.getName()).build();
            Intent intent = new Intent(Intent.ACTION_VIEW, uri, test.getTargetContext(), TranscribeFileActivity.class)
                    .setDataAndType(uri, "audio/wav")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            first = test.startActivitySync(intent);
            Activity cancelled = first;
            test.runOnMainSync(cancelled::finish);
            test.waitForIdleSync();
            second = test.startActivitySync(intent);
            // Deliberately deliver an old owner's cleanup after the new owner
            // initialized, matching delayed lifecycle cleanup without destroying it.
            java.lang.reflect.Method cleanup = TranscribeFileActivity.class
                    .getDeclaredMethod("cleanupNative", TranscribeFileActivity.class);
            cleanup.setAccessible(true);
            cleanup.invoke(first, first);
            Activity active = second;
            long deadline = SystemClock.elapsedRealtime() + 60000;
            boolean[] complete = new boolean[1];
            while (SystemClock.elapsedRealtime() < deadline) {
                test.runOnMainSync(() -> complete[0] =
                        active.findViewById(R.id.progress_area).getVisibility() == View.GONE
                        && active.findViewById(R.id.btn_copy).getVisibility() == View.VISIBLE
                        && ((TextView) active.findViewById(R.id.txt_result)).length() > 0);
                if (complete[0]) {
                    String[] transcript = new String[1];
                    test.runOnMainSync(() -> transcript[0] =
                            ((TextView) active.findViewById(R.id.txt_result)).getText().toString());
                    // The pinned public JFK fixture ends with this phrase. A
                    // non-empty result alone did not detect dropped EOF words.
                    String words = transcript[0].toLowerCase(java.util.Locale.ROOT)
                            .replaceAll("[^a-z]+", " ").trim();
                    if (!words.endsWith("for your country"))
                        throw new AssertionError("shared-file transcription lost the public fixture tail");
                    return;
                }
                SystemClock.sleep(100);
            }
            throw new AssertionError("shared-file transcription did not finish after stale-owner cleanup");
        } finally {
            Activity a = first, b = second;
            test.runOnMainSync(() -> { if (a != null) a.finish(); if (b != null) b.finish(); });
            test.waitForIdleSync();
            Files.deleteIfExists(audio.toPath());
        }
    }
}
