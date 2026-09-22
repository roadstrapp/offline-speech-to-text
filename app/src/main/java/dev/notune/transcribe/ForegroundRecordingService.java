package dev.notune.transcribe;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.PermissionChecker;

/** Sole owner for long-running microphone capture and on-device inference. */
public final class ForegroundRecordingService extends Service {
    static final String ACTION_START = "dev.notune.transcribe.m4.START";
    static final String ACTION_CANCEL_START = "dev.notune.transcribe.m4.CANCEL_START";
    static final String ACTION_STOP_DISCARD = "dev.notune.transcribe.m4.STOP_DISCARD";
    static final String ACTION_STOP_TRANSCRIBE = "dev.notune.transcribe.m4.STOP_TRANSCRIBE";
    static final String ACTION_DISCARD = "dev.notune.transcribe.m4.DISCARD";
    static final String ACTION_DELETE_RESULT = "dev.notune.transcribe.m4.DELETE_RESULT";
    static final String EXTRA_TOKEN = "session_token";
    static final String EXTRA_GENERATION = "session_generation";
    static final String EXTRA_NONCE = "notification_nonce";
    static final String EXTRA_STATE = "session_state";
    static final String EXTRA_EPOCH = "notification_epoch";
    static final int ACTIVE_NOTIFICATION_ID = 4101;
    static final int RESULT_NOTIFICATION_ID = 4102;
    static final int FAILURE_NOTIFICATION_ID = 4103;
    static final String CHANNEL_ID = "active_recording";
    static final String CHANNEL_NAME = "Active recording";
    static final String CHANNEL_DESCRIPTION = "Controls an active Offline Speech recording or transcription";
    private static final long START_TIMEOUT_MS = 30_000L;
    private static final long CAPTURE_TIMEOUT_MS = 10 * 60_000L;
    private static final long INFERENCE_TIMEOUT_MS = 150_000L;
    private static final long NOTIFICATION_CONFIRM_TIMEOUT_MS = 500L;
    private static final long NOTIFICATION_POLL_INTERVAL_MS = 500L;
    private static volatile ForegroundRecordingService activeInstance;

    private final ForegroundRecordingCoordinator coordinator = new ForegroundRecordingCoordinator();
    private final AudioFocusPauser audioFocus = new AudioFocusPauser();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RemoteCallbackList<IForegroundRecordingObserver> observers = new RemoteCallbackList<>();
    private boolean nativeInitialized;
    private boolean audioFocusActive;
    private long notificationEpoch;
    private int latestStartId;
    private volatile boolean destroyed;

    static {
        try {
            NativeLibraries.load();
        } catch (UnsatisfiedLinkError ignored) { }
    }

    @Override public void onCreate() {
        super.onCreate();
        destroyed = false;
        activeInstance = this;
        ensureChannel(this);
        initNative(this);
        nativeInitialized = true;
    }

    static boolean dispatchActiveAction(Intent intent) {
        ForegroundRecordingService service = activeInstance;
        if (service == null || intent == null) return false;
        Intent copy = new Intent(intent);
        service.main.post(() -> service.handleActiveAction(copy));
        return true;
    }

    static String resultTag(long generation) {
        return "m4-result-" + generation;
    }

    private void handleActiveAction(Intent intent) {
        String action = intent.getAction();
        if (ACTION_CANCEL_START.equals(action) || ACTION_STOP_DISCARD.equals(action)
                || ACTION_DISCARD.equals(action)) {
            discard(intent);
        } else if (ACTION_STOP_TRANSCRIBE.equals(action)) {
            stopForTranscription(intent);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = Math.max(latestStartId, startId);
        if (intent == null || intent.getAction() == null) {
            stopIfNoActiveSession(startId);
            return START_NOT_STICKY;
        }
        switch (intent.getAction()) {
            case ACTION_START:
                if (!canStartCapture()) {
                    stopIfNoActiveSession(startId);
                    break;
                }
                try {
                    startSession(startId);
                } catch (RuntimeException failure) {
                    ForegroundRecordingCoordinator.Session active = coordinator.session();
                    if (active != null) failSession(active.generation);
                    else {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        stopIfNoActiveSession(startId);
                    }
                }
                break;
            case ACTION_CANCEL_START:
            case ACTION_STOP_DISCARD:
            case ACTION_DISCARD:
                if (!discard(intent)) stopIfNoActiveSession(startId);
                break;
            case ACTION_STOP_TRANSCRIBE:
                if (!stopForTranscription(intent)) stopIfNoActiveSession(startId);
                break;
            default: stopIfNoActiveSession(startId); break;
        }
        return START_NOT_STICKY;
    }

    private boolean canStartCapture() {
        if (!ForegroundRecordingActivity.isResumedForStart()) return false;
        if (TransientResultVault.global().peek() != null) return false;
        if (!ForegroundRecordingActivity.hasUsableModel(this)) return false;
        if (!ForegroundRecordingActivity.hasVisibleMicrophonePermission(this)) return false;
        if (Build.VERSION.SDK_INT >= 33
                && PermissionChecker.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PermissionChecker.PERMISSION_GRANTED) return false;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
            if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                return false;
            }
        }
        return true;
    }

    private void startSession(int startId) {
        ForegroundRecordingCoordinator.Session session = coordinator.begin();
        if (session == null) {
            notifyObservers();
            stopIfNoActiveSession(startId);
            return;
        }
        long startingEpoch = showActive(
                ForegroundRecordingCoordinator.State.STARTING, session, true);
        notifyObservers();
        confirmNotificationThen(session, ForegroundRecordingCoordinator.State.STARTING,
                ForegroundRecordingCoordinator.State.STARTING, startingEpoch, () -> {
            if (coordinator.startCancelled(session.generation, session.attempt)) {
                finishDiscard(session.generation);
                return;
            }
            monitorActiveNotification(session,
                    ForegroundRecordingCoordinator.State.STARTING, startingEpoch);
            audioFocusActive = audioFocus.request(this, focusChange -> {
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                    main.post(() -> discardForPrivacyLoss(session));
                }
            });
            new Thread(() -> {
                try {
                    startRecording(session.generation, session.attempt);
                } catch (Throwable failure) {
                    main.post(() -> failSession(session.generation));
                }
            }, "m4-microphone-start").start();
        });
        main.postDelayed(() -> {
            ForegroundRecordingCoordinator.Session active = coordinator.session();
            if (active != null && active.generation == session.generation
                    && coordinator.state() == ForegroundRecordingCoordinator.State.STARTING) {
                failSession(session.generation);
            }
        }, START_TIMEOUT_MS);
    }

    private boolean discard(Intent intent) {
        ForegroundRecordingCoordinator.Session session = validated(intent);
        if (session == null) return false;
        ForegroundRecordingCoordinator.DiscardResult result =
                coordinator.discard(session.token, session.generation);
        if (result == ForegroundRecordingCoordinator.DiscardResult.REJECTED) return false;
        if (result == ForegroundRecordingCoordinator.DiscardResult.DEFERRED) return true;
        invalidateNotificationPoll();
        notifyObservers();
        cancelAndFinish(session.generation, "m4-discard");
        return true;
    }

    private void discardForPrivacyLoss(ForegroundRecordingCoordinator.Session session) {
        if (coordinator.discard(session.token, session.generation)
                != ForegroundRecordingCoordinator.DiscardResult.CLAIMED) return;
        invalidateNotificationPoll();
        notifyObservers();
        cancelAndFinish(session.generation, "m4-privacy-discard");
    }

    private boolean stopForTranscription(Intent intent) {
        ForegroundRecordingCoordinator.Session session = validated(intent);
        if (session == null || !coordinator.stopForTranscription(session.token, session.generation)) {
            return false;
        }
        invalidateNotificationPoll();
        notifyObservers();
        new Thread(() -> {
            try {
                boolean ended = endRecordingCapture();
                main.post(() -> {
                    if (ended) captureEnded(session); else failSession(session.generation);
                });
            } catch (Throwable failure) {
                main.post(() -> failSession(session.generation));
            }
        }, "m4-stop-capture").start();
        return true;
    }

    private void captureEnded(ForegroundRecordingCoordinator.Session session) {
        releaseFocus();
        if (!coordinator.captureEnded(session.generation)) return;
        long transcribingEpoch = showActive(
                ForegroundRecordingCoordinator.State.TRANSCRIBING, session, false);
        confirmNotificationThen(session, ForegroundRecordingCoordinator.State.TRANSCRIBING,
                ForegroundRecordingCoordinator.State.TRANSCRIBING, transcribingEpoch, () -> {
                    notifyObservers();
                    monitorActiveNotification(session,
                            ForegroundRecordingCoordinator.State.TRANSCRIBING,
                            transcribingEpoch);
                    new Thread(() -> {
                        try { beginTranscription(); }
                        catch (Throwable failure) {
                            main.post(() -> failSession(session.generation));
                        }
                    }, "m4-inference").start();
                });
        main.postDelayed(() -> {
            ForegroundRecordingCoordinator.Session active = coordinator.session();
            if (active != null && active.generation == session.generation
                    && coordinator.state() == ForegroundRecordingCoordinator.State.TRANSCRIBING) {
                failSession(session.generation);
            }
        }, INFERENCE_TIMEOUT_MS);
    }

    private ForegroundRecordingCoordinator.Session validated(Intent intent) {
        ForegroundRecordingCoordinator.Session active = coordinator.session();
        if (active == null) return null;
        String token = intent.getStringExtra(EXTRA_TOKEN);
        long generation = intent.getLongExtra(EXTRA_GENERATION, -1);
        String nonce = intent.getStringExtra(EXTRA_NONCE);
        return active.generation == generation && active.token.equals(token)
                && active.notificationNonce.equals(nonce) ? active : null;
    }

    private void finishDiscard(long generation) {
        if (destroyed) return;
        invalidateNotificationPoll();
        releaseFocus();
        ForegroundRecordingCoordinator.Session active = coordinator.session();
        if (active != null) {
            TransientResultVault.global().rollback(active.token, generation, active.notificationNonce);
            TransientResultVault.global().clear(active.token, generation, active.notificationNonce);
        }
        coordinator.finishDiscard(generation);
        notifyObservers();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .cancel(ACTIVE_NOTIFICATION_ID);
        stopSelfResult(latestStartId);
    }

    private void cancelAndFinish(long generation, String threadName) {
        new Thread(() -> {
            try { cancelRecording(); } catch (Throwable ignored) { }
            main.post(() -> finishDiscard(generation));
        }, threadName).start();
    }

    private void failSession(long generation) {
        if (!coordinator.fail(generation)) return;
        ForegroundRecordingActivity.reportStartFailure();
        postFailure();
        invalidateNotificationPoll();
        notifyObservers();
        cancelAndFinish(generation, "m4-failed-cleanup");
    }

    /** Native gate queried immediately before building and playing the stream. */
    public boolean isStartCancelled(long generation, long attempt) {
        return destroyed || coordinator.startCancelled(generation, attempt);
    }

    /** Native returns 0 only after the stream is playing; 1 is cancelled, 2 is failed. */
    public void onNativeStartResult(long generation, long attempt, int result) {
        main.post(() -> {
            if (destroyed) return;
            if (result == 0 && coordinator.nativeStarted(generation, attempt)) {
                ForegroundRecordingCoordinator.Session session = coordinator.session();
                if (session != null) {
                    long recordingEpoch = showActive(
                            ForegroundRecordingCoordinator.State.RECORDING, session, true);
                    confirmNotificationThen(session,
                            ForegroundRecordingCoordinator.State.STARTING,
                            ForegroundRecordingCoordinator.State.RECORDING,
                            recordingEpoch, () -> {
                                if (!coordinator.confirmRecording(session.generation)) {
                                    failSession(session.generation);
                                    return;
                                }
                                notifyObservers();
                                monitorActiveNotification(session,
                                        ForegroundRecordingCoordinator.State.RECORDING,
                                        recordingEpoch);
                                main.postDelayed(() -> {
                                    ForegroundRecordingCoordinator.Session active = coordinator.session();
                                    if (active != null && active.generation == session.generation
                                            && coordinator.state()
                                            == ForegroundRecordingCoordinator.State.RECORDING) {
                                        stopForTranscription(commandFor(ACTION_STOP_TRANSCRIBE, session));
                                    }
                                }, CAPTURE_TIMEOUT_MS);
                            });
                }
            } else if (result == 1) {
                cancelAndFinish(generation, "m4-cancelled-start");
            } else {
                failSession(generation);
            }
        });
    }

    /** Native capture watchdog or stream error; generation fences stale failures. */
    public void onNativeCaptureError(long generation) {
        main.post(() -> {
            if (destroyed) return;
            ForegroundRecordingCoordinator.Session active = coordinator.session();
            if (active != null && active.generation == generation
                    && coordinator.state() == ForegroundRecordingCoordinator.State.RECORDING) {
                failSession(generation);
            }
        });
    }

    /** Generic status is never a start-success signal. */
    public void onStatusUpdate(String status) {
        if (!destroyed && status != null && status.startsWith("Error")
                && coordinator.state() != ForegroundRecordingCoordinator.State.STARTING) {
            ForegroundRecordingCoordinator.Session session = coordinator.session();
            if (session != null) main.post(() -> failSession(session.generation));
        }
    }

    /** Called from native code; publication is staged until both notification checks pass. */
    public void onTextTranscribed(String text) {
        if (!destroyed) main.post(() -> {
            if (!destroyed) commitResult(text);
        });
    }

    private void commitResult(String text) {
        ForegroundRecordingCoordinator.Session session = coordinator.session();
        if (session == null || coordinator.state() != ForegroundRecordingCoordinator.State.TRANSCRIBING) return;
        if (text == null || text.trim().isEmpty()) {
            failSession(session.generation);
            return;
        }
        if (!ResultNotification.canPost(this)) {
            failSession(session.generation);
            return;
        }
        if (!coordinator.beginPublication(session.generation)) return;
        long expires = System.currentTimeMillis() + TransientResultVault.TTL_MILLIS;
        if (!TransientResultVault.global().stage(text, session.token, session.generation,
                session.notificationNonce, expires)) {
            TransientResultVault.global().rollback(session.token, session.generation,
                    session.notificationNonce);
            failSession(session.generation);
            return;
        }
        postResult(session, text, expires);
        confirmResultThen(session, () -> {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            confirmActiveNotificationAbsentThen(session, () -> {
                if (!TransientResultVault.global().commit(session.token, session.generation,
                        session.notificationNonce)
                        || !coordinator.resultReady(session.generation)) {
                    TransientResultVault.global().rollback(session.token, session.generation,
                            session.notificationNonce);
                    cancelResultNotification(session.generation);
                    failSession(session.generation);
                    return;
                }
                boolean discardRequested = coordinator.finishPublication(session.generation);
                ResultExpiryScheduler.schedule(this, session.token, session.generation,
                        session.notificationNonce, expires);
                notifyObservers();
                coordinator.finishService(session.generation);
                if (discardRequested) {
                    TransientResultVault.global().clear(session.token, session.generation,
                            session.notificationNonce);
                    cancelResultNotification(session.generation);
                }
                stopSelfResult(latestStartId);
            });
        });
    }

    public void onAudioLevel(float ignored) { }
    /** Content-free timing emitted only when the user opted into diagnostics. */
    public void onTranscriptionMetrics(long audioMs, long computeMs, int threads) {
        DiagnosticLog.event(getFilesDir(), "timing",
                "process=service,audio_ms=" + Math.max(0L, audioMs)
                        + ",duration_ms=" + Math.max(0L, computeMs)
                        + ",cpu_threads=" + Math.max(0, threads));
    }
    public void onAutoStop() {
        main.post(() -> {
            if (destroyed) return;
            ForegroundRecordingCoordinator.Session session = coordinator.session();
            if (session != null
                    && coordinator.state() == ForegroundRecordingCoordinator.State.RECORDING) {
                stopForTranscription(commandFor(ACTION_STOP_TRANSCRIBE, session));
            }
        });
    }

    private long showActive(ForegroundRecordingCoordinator.State presentationState,
                            ForegroundRecordingCoordinator.Session session, boolean capture) {
        NotificationContract.Form form = NotificationContract.forState(presentationState);
        long epoch = ++notificationEpoch;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(form.title)
                .setContentText(form.text)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setDeleteIntent(commandIntent(ACTION_DISCARD, session))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(openControlIntent(session));
        b.getExtras().putLong(EXTRA_GENERATION, session.generation);
        b.getExtras().putString(EXTRA_NONCE, session.notificationNonce);
        b.getExtras().putString(EXTRA_STATE, presentationState.name());
        b.getExtras().putLong(EXTRA_EPOCH, epoch);
        if (presentationState == ForegroundRecordingCoordinator.State.RECORDING) {
            b.setWhen(session.startedAtMillis).setUsesChronometer(true);
        }
        for (String action : form.actions) {
            String serviceAction;
            if (NotificationContract.CANCEL_START.equals(action)) {
                serviceAction = ACTION_CANCEL_START;
            } else if (NotificationContract.STOP_DISCARD.equals(action)) {
                serviceAction = ACTION_STOP_DISCARD;
            } else if (NotificationContract.STOP_TRANSCRIBE.equals(action)) {
                serviceAction = ACTION_STOP_TRANSCRIBE;
            } else {
                serviceAction = ACTION_DISCARD;
            }
            b.addAction(0, NotificationContract.labelFor(action),
                    commandIntent(serviceAction, session));
        }
        startForegroundCompat(b.build(), capture);
        return epoch;
    }

    private PendingIntent openControlIntent(ForegroundRecordingCoordinator.Session session) {
        Intent i = new Intent(this, ForegroundRecordingActivity.class)
                .setData(Uri.parse("ostt://m4/control/" + session.generation + "/" + session.notificationNonce))
                .putExtra(EXTRA_TOKEN, session.token)
                .putExtra(EXTRA_GENERATION, session.generation)
                .putExtra(EXTRA_NONCE, session.notificationNonce);
        return PendingIntent.getActivity(this, (int) (session.generation & 0x7fffffff), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent openControlIntent() {
        Intent i = new Intent(this, ForegroundRecordingActivity.class)
                .setData(Uri.parse("ostt://m4/control"));
        return PendingIntent.getActivity(this, 4099, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent commandIntent(String action, ForegroundRecordingCoordinator.Session session) {
        Uri identity = Uri.parse("ostt://m4/" + action + "/" + session.generation + "/" + session.notificationNonce);
        Intent i = new Intent(this, ForegroundRecordingService.class)
                .setAction(action).setData(identity)
                .putExtra(EXTRA_TOKEN, session.token)
                .putExtra(EXTRA_GENERATION, session.generation)
                .putExtra(EXTRA_NONCE, session.notificationNonce);
        int requestCode = (int) ((session.generation ^ action.hashCode()) & 0x7fffffff);
        return PendingIntent.getService(this, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void postResult(ForegroundRecordingCoordinator.Session session, String text,
                            long expiresAtMillis) {
        ResultNotification.post(this, new TransientResultVault.Entry(
                text, session.token, session.generation, session.notificationNonce,
                expiresAtMillis));
    }

    private void postFailure() {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_error)
                .setContentTitle("Recording stopped")
                .setContentText("Open the app to check permissions and try again")
                .setContentIntent(openControlIntent())
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(
                FAILURE_NOTIFICATION_ID, b.build());
    }

    private void confirmNotificationThen(ForegroundRecordingCoordinator.Session session,
                                         ForegroundRecordingCoordinator.State coordinatorState,
                                         ForegroundRecordingCoordinator.State notificationState,
                                         long expectedEpoch,
                                         Runnable confirmed) {
        final long started = android.os.SystemClock.elapsedRealtime();
        Runnable check = new Runnable() {
            @Override public void run() {
                if (notificationEpoch != expectedEpoch || coordinator.session() != session
                        || coordinator.state() != coordinatorState) {
                    return;
                }
                if (notificationMatches(ACTIVE_NOTIFICATION_ID, session,
                        notificationState, expectedEpoch)) {
                    confirmed.run();
                } else if (android.os.SystemClock.elapsedRealtime() - started < NOTIFICATION_CONFIRM_TIMEOUT_MS) {
                    main.postDelayed(this, 50L);
                } else {
                    failSession(session.generation);
                }
            }
        };
        main.post(check);
    }

    private void confirmResultThen(ForegroundRecordingCoordinator.Session session, Runnable confirmed) {
        final long started = android.os.SystemClock.elapsedRealtime();
        Runnable check = new Runnable() {
            @Override public void run() {
                if (!ResultNotification.canPost(ForegroundRecordingService.this)) {
                    TransientResultVault.global().rollback(session.token, session.generation,
                            session.notificationNonce);
                    cancelResultNotification(session.generation);
                    failSession(session.generation);
                } else if (notificationMatches(RESULT_NOTIFICATION_ID, session,
                        ForegroundRecordingCoordinator.State.RESULT_READY, -1L)) {
                    confirmed.run();
                } else if (android.os.SystemClock.elapsedRealtime() - started < NOTIFICATION_CONFIRM_TIMEOUT_MS) {
                    main.postDelayed(this, 50L);
                } else {
                    TransientResultVault.global().rollback(session.token, session.generation,
                            session.notificationNonce);
                    cancelResultNotification(session.generation);
                    failSession(session.generation);
                }
            }
        };
        main.post(check);
    }

    private void confirmActiveNotificationAbsentThen(
            ForegroundRecordingCoordinator.Session session, Runnable confirmed) {
        final long started = android.os.SystemClock.elapsedRealtime();
        Runnable check = new Runnable() {
            @Override public void run() {
                if (!ResultNotification.canPost(ForegroundRecordingService.this)
                        || !notificationMatches(RESULT_NOTIFICATION_ID, session,
                        ForegroundRecordingCoordinator.State.RESULT_READY, -1L)) {
                    TransientResultVault.global().rollback(session.token, session.generation,
                            session.notificationNonce);
                    cancelResultNotification(session.generation);
                    failSession(session.generation);
                } else if (!notificationPresent(ACTIVE_NOTIFICATION_ID, null)) {
                    confirmed.run();
                } else if (android.os.SystemClock.elapsedRealtime() - started
                        < NOTIFICATION_CONFIRM_TIMEOUT_MS) {
                    main.postDelayed(this, 50L);
                } else {
                    TransientResultVault.global().rollback(session.token, session.generation,
                            session.notificationNonce);
                    cancelResultNotification(session.generation);
                    failSession(session.generation);
                }
            }
        };
        main.post(check);
    }

    private void monitorActiveNotification(ForegroundRecordingCoordinator.Session session,
                                           ForegroundRecordingCoordinator.State expected,
                                           long expectedEpoch) {
        main.postDelayed(() -> {
            if (coordinator.session() != session || coordinator.state() != expected
                    || notificationEpoch != expectedEpoch) return;
            boolean matches = notificationMatches(
                    ACTIVE_NOTIFICATION_ID, session, expected, expectedEpoch);
            if (coordinator.session() != session || coordinator.state() != expected
                    || notificationEpoch != expectedEpoch) return;
            if (!matches) {
                discard(commandFor(ACTION_DISCARD, session));
            } else {
                monitorActiveNotification(session, expected, expectedEpoch);
            }
        }, NOTIFICATION_POLL_INTERVAL_MS);
    }

    private void invalidateNotificationPoll() {
        notificationEpoch++;
    }

    private Intent commandFor(String action, ForegroundRecordingCoordinator.Session session) {
        return new Intent().setAction(action)
                .putExtra(EXTRA_TOKEN, session.token)
                .putExtra(EXTRA_GENERATION, session.generation)
                .putExtra(EXTRA_NONCE, session.notificationNonce);
    }

    private boolean notificationMatches(int id, ForegroundRecordingCoordinator.Session session,
                                        ForegroundRecordingCoordinator.State state,
                                        long expectedEpoch) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        StatusBarNotification[] notifications;
        try {
            notifications = nm.getActiveNotifications();
        } catch (RuntimeException unavailable) {
            return false;
        }
        for (StatusBarNotification sbn : notifications) {
            if (sbn.getId() != id) continue;
            if (id == ACTIVE_NOTIFICATION_ID && sbn.getTag() != null) return false;
            if (id == RESULT_NOTIFICATION_ID
                    && !resultTag(session.generation).equals(sbn.getTag())) return false;
            Notification notification = sbn.getNotification();
            android.os.Bundle extras = notification.extras;
            if (!session.notificationNonce.equals(extras.getString(EXTRA_NONCE))
                    || session.generation != extras.getLong(EXTRA_GENERATION, -1)
                    || !state.name().equals(extras.getString(EXTRA_STATE))) return false;
            if (expectedEpoch >= 0 && expectedEpoch != extras.getLong(EXTRA_EPOCH, -1)) {
                return false;
            }
            if (id == RESULT_NOTIFICATION_ID) {
                return notification.actions != null && notification.actions.length == 1
                        && "Review".contentEquals(notification.actions[0].title);
            }
            String[] expectedActions = NotificationContract.forState(state).actions;
            if (notification.actions == null || notification.actions.length != expectedActions.length) return false;
            for (int i = 0; i < expectedActions.length; i++) {
                if (!actionLabel(expectedActions[i]).contentEquals(notification.actions[i].title)) return false;
            }
            return true;
        }
        return false;
    }

    private boolean notificationPresent(int id, String tag) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            for (StatusBarNotification sbn : nm.getActiveNotifications()) {
                if (sbn.getId() == id
                        && (tag == null ? sbn.getTag() == null : tag.equals(sbn.getTag()))) {
                    return true;
                }
            }
        } catch (RuntimeException unavailable) {
            return true;
        }
        return false;
    }

    private void cancelResultNotification(long generation) {
        ResultNotification.cancel(this, generation);
    }

    private static String actionLabel(String action) {
        return NotificationContract.labelFor(action);
    }

    private void startForegroundCompat(Notification notification, boolean capture) {
        int policy = capture ? ForegroundTypePolicy.captureType(Build.VERSION.SDK_INT)
                : ForegroundTypePolicy.inferenceType(Build.VERSION.SDK_INT);
        int type = policy == ForegroundTypePolicy.MICROPHONE
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                : policy == ForegroundTypePolicy.SHORT_SERVICE
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE : 0;
        ServiceCompat.startForeground(this, ACTIVE_NOTIFICATION_ID, notification, type);
    }

    static void ensureChannel(android.content.Context context) {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(CHANNEL_DESCRIPTION);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ((NotificationManager) context.getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(channel);
    }

    private void releaseFocus() {
        if (audioFocusActive) {
            audioFocus.abandon(this);
            audioFocusActive = false;
        }
    }

    private void stopIfNoActiveSession(int startId) {
        if (ForegroundServiceStopPolicy.shouldStop(coordinator)) {
            stopSelfResult(startId);
        }
    }

    public void onTimeout(int startId) {
        discardTimedOutSession(startId);
    }

    public void onTimeout(int startId, int foregroundServiceType) {
        discardTimedOutSession(startId);
    }

    private void discardTimedOutSession(int startId) {
        if (destroyed) return;
        ForegroundRecordingCoordinator.Session session = coordinator.session();
        if (session != null && coordinator.state() == ForegroundRecordingCoordinator.State.TRANSCRIBING
                && coordinator.discard(session.token, session.generation)
                == ForegroundRecordingCoordinator.DiscardResult.CLAIMED) {
            try { cancelRecording(); } catch (Throwable ignored) { }
            finishDiscard(session.generation);
        } else {
            stopSelfResult(startId);
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // The explicit foreground session outlives removal of the launch task.
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (activeInstance == this) activeInstance = null;
        main.removeCallbacksAndMessages(null);
        ForegroundRecordingCoordinator.Session session = coordinator.session();
        if (nativeInitialized) {
            try { cancelRecording(); } catch (Throwable ignored) { }
        }
        if (session != null) {
            coordinator.fail(session.generation);
            releaseFocus();
            TransientResultVault.global().rollback(
                    session.token, session.generation, session.notificationNonce);
            TransientResultVault.global().clear(
                    session.token, session.generation, session.notificationNonce);
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .cancel(ACTIVE_NOTIFICATION_ID);
            coordinator.finishDiscard(session.generation);
        }
        if (nativeInitialized) {
            try { cleanupNative(); } catch (Throwable ignored) { }
            nativeInitialized = false;
        }
        observers.kill();
        super.onDestroy();
    }

    private final IForegroundRecordingService.Stub binder = new IForegroundRecordingService.Stub() {
        private boolean sameUid() { return Binder.getCallingUid() == Process.myUid(); }
        @Override public int getState() {
            return sameUid() ? coordinator.state().ordinal()
                    : ForegroundRecordingCoordinator.State.IDLE.ordinal();
        }
        @Override public long getGeneration() {
            if (!sameUid()) return -1;
            ForegroundRecordingCoordinator.Session s = coordinator.session();
            return s == null ? -1 : s.generation;
        }
        @Override public boolean matchesSession(String token, long generation, String notificationNonce) {
            if (!sameUid()) return false;
            ForegroundRecordingCoordinator.Session s = coordinator.session();
            return s != null && s.generation == generation
                    && s.token.equals(token) && s.notificationNonce.equals(notificationNonce);
        }
        @Override public boolean performAction(int action, long generation) {
            if (!sameUid()) return false;
            ForegroundRecordingCoordinator.Session s = coordinator.session();
            if (s == null || s.generation != generation) return false;
            if (action == 1) {
                main.post(() -> discard(commandFor(ACTION_DISCARD, s)));
                return true;
            }
            if (action == 2 && coordinator.state()
                    == ForegroundRecordingCoordinator.State.RECORDING) {
                main.post(() -> stopForTranscription(commandFor(ACTION_STOP_TRANSCRIBE, s)));
                return true;
            }
            return false;
        }
        @Override public void registerObserver(IForegroundRecordingObserver observer) {
            if (sameUid() && observer != null) {
                observers.register(observer);
                try { observer.onStateChanged(getState(), getGeneration()); } catch (Exception ignored) { }
            }
        }
        @Override public void unregisterObserver(IForegroundRecordingObserver observer) {
            if (sameUid() && observer != null) observers.unregister(observer);
        }
    };

    private void notifyObservers() {
        int count = observers.beginBroadcast();
        int state = coordinator.state().ordinal();
        ForegroundRecordingCoordinator.Session s = coordinator.session();
        long generation = s == null ? -1 : s.generation;
        for (int i = 0; i < count; i++) {
            try { observers.getBroadcastItem(i).onStateChanged(state, generation); }
            catch (Exception ignored) { }
        }
        observers.finishBroadcast();
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    private native void initNative(ForegroundRecordingService service);
    private native void cleanupNative();
    private native void startRecording(long generation, long attempt);
    private native boolean endRecordingCapture();
    private native void beginTranscription();
    private native void cancelRecording();
}
