package dev.notune.transcribe;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.PermissionChecker;

/** Detached, content-free result notification that can be restored after review is closed. */
final class ResultNotification {
    private ResultNotification() { }

    static boolean canPost(Context context) {
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 33
                && PermissionChecker.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PermissionChecker.PERMISSION_GRANTED) return false;
        if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager.getNotificationChannel(
                    ForegroundRecordingService.CHANNEL_ID);
            return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    static void post(Context context, TransientResultVault.Entry entry) {
        Intent view = new Intent(context, ForegroundResultActivity.class)
                .setData(Uri.parse("ostt://m4/result/" + entry.generation + "/" + entry.nonce))
                .putExtra(ForegroundRecordingService.EXTRA_TOKEN, entry.token)
                .putExtra(ForegroundRecordingService.EXTRA_GENERATION, entry.generation)
                .putExtra(ForegroundRecordingService.EXTRA_NONCE, entry.nonce);
        PendingIntent content = PendingIntent.getActivity(context, (int) entry.generation, view,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent deleteCommand = new Intent(context, ForegroundRecordingActionReceiver.class)
                .setAction(ForegroundRecordingService.ACTION_DELETE_RESULT)
                .setData(Uri.parse("ostt://m4/delete-result/" + entry.generation + "/" + entry.nonce))
                .putExtra(ForegroundRecordingService.EXTRA_TOKEN, entry.token)
                .putExtra(ForegroundRecordingService.EXTRA_GENERATION, entry.generation)
                .putExtra(ForegroundRecordingService.EXTRA_NONCE, entry.nonce);
        PendingIntent delete = PendingIntent.getBroadcast(context,
                (int) ((entry.generation ^ ForegroundRecordingService.ACTION_DELETE_RESULT.hashCode())
                        & 0x7fffffff),
                deleteCommand, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context, ForegroundRecordingService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_check_circle)
                .setContentTitle("Transcription ready")
                .setContentText("Review before using")
                .setContentIntent(content)
                .addAction(0, "Review", content)
                .setDeleteIntent(delete)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setTimeoutAfter(Math.max(1L,
                        entry.expiresAtMillis - System.currentTimeMillis()))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        builder.getExtras().putLong(
                ForegroundRecordingService.EXTRA_GENERATION, entry.generation);
        builder.getExtras().putString(ForegroundRecordingService.EXTRA_NONCE, entry.nonce);
        builder.getExtras().putString(ForegroundRecordingService.EXTRA_STATE,
                ForegroundRecordingCoordinator.State.RESULT_READY.name());
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).notify(
                ForegroundRecordingService.resultTag(entry.generation),
                ForegroundRecordingService.RESULT_NOTIFICATION_ID,
                builder.build());
    }

    static void cancel(Context context, long generation) {
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(
                ForegroundRecordingService.resultTag(generation),
                ForegroundRecordingService.RESULT_NOTIFICATION_ID);
    }
}
