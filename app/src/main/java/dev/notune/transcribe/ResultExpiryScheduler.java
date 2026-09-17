package dev.notune.transcribe;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** Process-held expiry that contains identifiers only, never transcript content. */
final class ResultExpiryScheduler {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ResultExpiryScheduler() { }

    static void schedule(Context context, String token, long generation, String nonce,
                         long expiresAtMillis) {
        Context app = context.getApplicationContext();
        long delay = Math.max(1L, expiresAtMillis - System.currentTimeMillis());
        MAIN.postDelayed(() -> {
            if (TransientResultVault.global().expire(token, generation, nonce)) {
                ResultNotification.cancel(app, generation);
            }
        }, delay);
    }
}
