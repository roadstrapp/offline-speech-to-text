package dev.notune.transcribe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Actual JNI capture and microphone FGS with explicitly simulated editor lifecycle.
 * No system keyboard/default setting or screen-lock setting is modified. */
final class ImeContinuityDeviceTest {
    public static final class ProbeIme extends RustInputMethodService {
        volatile int levels;
        volatile String error;
        final EditorInfo editor = new EditorInfo();
        InputConnection connection;
        void attach(Context context) { attachBaseContext(context); }
        @Override public EditorInfo getCurrentInputEditorInfo() { return editor; }
        @Override public InputConnection getCurrentInputConnection() { return connection; }
        @Override public void onAudioLevel(float level) { levels++; super.onAudioLevel(level); }
        @Override public void onStatusUpdate(String status) {
            if (NativeStatus.isError(status)) error = status;
            super.onStatusUpdate(status);
        }
    }
    private static Field field(String name) throws Exception {
        Field f = RustInputMethodService.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static void call(ProbeIme ime, String name) throws Exception {
        Method m = RustInputMethodService.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(ime);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static void run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        check(context.getPackageName().equals("app.offlinespeechtotext.validation"), "validation only");
        Activity foreground = test.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ProbeIme ime = new ProbeIme();
        final ImeKeepAliveState[] state = new ImeKeepAliveState[1];
        try {
            test.runOnMainSync(() -> {
                try {
                    ime.attach(context); ime.onCreate();
                    ime.editor.packageName = context.getPackageName();
                    ime.editor.inputType = InputType.TYPE_CLASS_TEXT;
                    ime.connection = new BaseInputConnection(new View(foreground), false);
                    field("windowVisible").setBoolean(ime, true);
                    field("inputActive").setBoolean(ime, true);
                    field("editorSafeForDictation").setBoolean(ime, true);
                    ((ImeRecordingSession) field("recordingSession").get(ime))
                            .begin(ime.connection, 1, context.getPackageName());
                    Method update = RustInputMethodService.class.getDeclaredMethod("updateRecordButtonUI", boolean.class);
                    update.setAccessible(true); update.invoke(ime, true);
                    Method start = RustInputMethodService.class.getDeclaredMethod("startRecording", boolean.class, int.class);
                    start.setAccessible(true); start.invoke(ime, false, 2000);
                    call(ime, "beginKeepAlive");
                    state[0] = (ImeKeepAliveState) field("keepAlive").get(ime);
                } catch (Exception e) { throw new AssertionError(e); }
            });
            long end = SystemClock.elapsedRealtime() + 20_000;
            boolean[] ready = {false};
            while (SystemClock.elapsedRealtime() < end) {
                test.runOnMainSync(() -> ready[0] = state[0].ready());
                if (ready[0] && ime.levels > 0) break;
                if (ime.error != null) throw new AssertionError("capture startup failed");
                SystemClock.sleep(50);
            }
            check(ready[0] && ime.levels > 0, "foreground promotion or actual input not ready");
            int before = ime.levels;
            test.runOnMainSync(() -> { ime.onWindowHidden(); ime.onFinishInput(); });
            SystemClock.sleep(1800);
            check(ime.levels > before && ime.error == null, "capture stopped during simulated hide/finish");
            android.app.PendingIntent stop = null;
            for (android.service.notification.StatusBarNotification notification : context
                    .getSystemService(android.app.NotificationManager.class).getActiveNotifications()) {
                if (notification.getId() == 4107) {
                    check(notification.getNotification().actions != null
                            && notification.getNotification().actions.length == 1, "Stop action missing");
                    stop = notification.getNotification().actions[0].actionIntent;
                }
            }
            check(stop != null, "dictation notification missing");
            stop.send();
            boolean[] stopped = {false};
            end = SystemClock.elapsedRealtime() + 5000;
            while (SystemClock.elapsedRealtime() < end) {
                test.runOnMainSync(() -> {
                    try { stopped[0] = !field("isRecording").getBoolean(ime) && !state[0].pending(); }
                    catch (Exception e) { throw new AssertionError(e); }
                });
                if (stopped[0]) break;
                SystemClock.sleep(50);
            }
            check(stopped[0], "notification Stop did not retire capture/service");
            end = SystemClock.elapsedRealtime() + 5000;
            boolean retired = false;
            while (SystemClock.elapsedRealtime() < end) {
                boolean notificationPresent = false;
                for (android.service.notification.StatusBarNotification notification : context
                        .getSystemService(android.app.NotificationManager.class).getActiveNotifications()) {
                    if (notification.getId() == 4107) notificationPresent = true;
                }
                Field active = ImeDictationService.class.getDeclaredField("active");
                active.setAccessible(true);
                boolean[] servicePresent = {false};
                test.runOnMainSync(() -> {
                    try { servicePresent[0] = active.get(null) != null; }
                    catch (Exception e) { throw new AssertionError(e); }
                });
                if (!notificationPresent && !servicePresent[0]) { retired = true; break; }
                SystemClock.sleep(50);
            }
            check(retired, "actual foreground service or notification retained after Stop");
            test.runOnMainSync(() -> {
                try {
                    check(!state[0].pending(), "microphone service retained during inference");
                } catch (Exception e) { throw new AssertionError(e); }
            });
            // Captured ambient audio is not transcribed into or exported from a personal editor.
            // Stop above exercises the real native capture-to-inference handoff; no text is collected.
        } finally {
            test.runOnMainSync(() -> {
                try { call(ime, "cancelRecording"); call(ime, "endKeepAlive"); ime.onDestroy(); }
                catch (Exception e) { throw new AssertionError(e); }
                finally { foreground.finish(); }
            });
            test.waitForIdleSync();
        }
    }
}
