package dev.notune.transcribe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Notification actions are capability-bound to a token, generation, nonce, and action. */
public final class ForegroundRecordingActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ForegroundRecordingService.ACTION_DELETE_RESULT.equals(action)) {
            String token = intent.getStringExtra(ForegroundRecordingService.EXTRA_TOKEN);
            long generation = intent.getLongExtra(ForegroundRecordingService.EXTRA_GENERATION, -1);
            String nonce = intent.getStringExtra(ForegroundRecordingService.EXTRA_NONCE);
            TransientResultVault.global().clear(token, generation, nonce);
            ((android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                    .cancel(ForegroundRecordingService.resultTag(generation),
                            ForegroundRecordingService.RESULT_NOTIFICATION_ID);
            return;
        }
        // A stale control after teardown is a content-free no-op and cannot restart capture.
        ForegroundRecordingService.dispatchActiveAction(intent);
    }
}
