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
    private static final long WAKE_LOCK_MS = 6L * 60 * 60 * 1000;

    private PowerManager.WakeLock wakeLock;

    /** Starts the service; false when Android refuses a microphone foreground service now. */
    static boolean start(Context context) {
        try {
            context.startForegroundService(new Intent(context, ImeDictationService.class));
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "keep-alive refused; dictation stops when the keyboard hides", e);
            return false;
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, ImeDictationService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "stop from the notification");
            RustInputMethodService.stopDictationFromNotification();
            return START_NOT_STICKY;
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (RuntimeException e) {
            Log.w(TAG, "foreground start refused", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ostt:ime-dictation");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKE_LOCK_MS);
        }
        Log.i(TAG, "keep-alive started");
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
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
                new Intent(this, ImeDictationService.class).setAction(ACTION_STOP),
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
