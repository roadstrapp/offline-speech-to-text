package dev.notune.transcribe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * Keeps a keyboard dictation recording while the keyboard is hidden: screen
 * locked, another app opened, the text field gone. An input method only has
 * microphone access while its window is shown; this foreground service of type
 * microphone, started while it is, carries the capture until the user stops
 * it. The notification's Stop ends the dictation like the keyboard's stop key;
 * the text then goes to the editor it was dictated for, or to private recovery.
 */
public final class ImeDictationService extends Service {
    static final String TAG = "OSTT-IME";
    private static final String CHANNEL_ID = "ime_dictation";
    private static final int NOTIFICATION_ID = 4107;
    private static final String ACTION_STOP = "dev.notune.transcribe.action.STOP_IME_DICTATION";
    /** Hard cap for a leaked lock; dictation is far shorter. */
    private static final long WAKE_LOCK_MS = 12L * 60 * 1000;
    private static final String EXTRA_TOKEN = "dictation_token";
    private final Handler main = new Handler(Looper.getMainLooper());
    private long token;
    private int latestStartId;
    private static ImeDictationService active;
    private final Runnable expire = () -> {
        RustInputMethodService.stopDictationFromNotification(token);
        stopSelf(latestStartId);
    };

    private PowerManager.WakeLock wakeLock;

    /** Starts the service; false when Android refuses a microphone foreground service now. */
    static boolean start(Context context, long token) {
        try {
            NotificationManager notifications = context.getSystemService(NotificationManager.class);
            if (notifications == null || !notifications.areNotificationsEnabled()) return false;
            NotificationChannel channel = notifications.getNotificationChannel(CHANNEL_ID);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
            context.startForegroundService(new Intent(context, ImeDictationService.class)
                    .putExtra(EXTRA_TOKEN, token));
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "keep-alive refused; input lifecycle will stop for recovery", e);
            return false;
        }
    }

    static void stop(Context context, long token) {
        if (active != null && active.token == token) {
            context.stopService(new Intent(context, ImeDictationService.class));
        }
        // A queued start without a live service checks ownership before promotion.
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "stop from the notification");
            long requested = intent.getLongExtra(EXTRA_TOKEN, 0);
            if (requested == token) {
                RustInputMethodService.stopDictationFromNotification(token);
                stopSelf(startId);
            } else if (!RustInputMethodService.ownsDictation(token)) {
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        long requested = intent == null ? 0 : intent.getLongExtra(EXTRA_TOKEN, 0);
        // Discharge startForegroundService's obligation even after a rapid Cancel.
        // A stale queued start must preserve a newer live recording's notification.
        if (RustInputMethodService.ownsDictation(requested)
                || !RustInputMethodService.ownsDictation(token)) token = requested;
        active = this;
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (RuntimeException e) {
            Log.w(TAG, "foreground start refused", e);
            RustInputMethodService.dictationForegroundUnavailable(token);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!RustInputMethodService.dictationForegroundReady(token)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        main.removeCallbacks(expire);
        main.postDelayed(expire, WAKE_LOCK_MS);
        if (wakeLock == null) {
            // a missing wake lock costs at most some audio with the screen off; never the dictation
            try {
                PowerManager.WakeLock lock = getSystemService(PowerManager.class)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ostt:ime-dictation");
                lock.setReferenceCounted(false);
                wakeLock = lock;
            } catch (RuntimeException e) {
                Log.w(TAG, "no wake lock for dictation", e);
            }
        }
        if (wakeLock != null) {
            try { wakeLock.acquire(WAKE_LOCK_MS); }
            catch (RuntimeException e) { Log.w(TAG, "no wake lock for dictation", e); }
        }
        Log.i(TAG, "keep-alive started");
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (active == this) active = null;
        main.removeCallbacks(expire);
        RustInputMethodService.dictationForegroundUnavailable(token);
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (RuntimeException ignored) { }
        }
        wakeLock = null;
        Log.i(TAG, "keep-alive stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                getString(R.string.ime_dictation_channel), NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, ImeDictationService.class).setAction(ACTION_STOP)
                        .setData(Uri.parse("ostt://ime-dictation/" + token))
                        .putExtra(EXTRA_TOKEN, token),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(getString(R.string.ime_dictation_title))
                .setContentText(getString(R.string.ime_dictation_text))
                .setOngoing(true)
                .setSilent(true)
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis())
                .addAction(0, getString(R.string.ime_dictation_stop), stop)
                .build();
    }
}
