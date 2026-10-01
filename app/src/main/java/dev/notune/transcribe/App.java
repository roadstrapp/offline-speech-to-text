package dev.notune.transcribe;

import android.app.Application;
import android.util.Log;

/**
 * Applies the saved dark-mode choice before any activity is created. Runs in
 * every process (including the ":ime" keyboard process). Dynamic color is not
 * applied because it would replace the app's true-black AMOLED night palette.
 */
public class App extends Application {
    private static final String TAG = "App";

    @Override
    public void onCreate() {
        super.onCreate();
        Thread migration = new Thread(() -> {
            try {
                SettingsSchema.migrate(getFilesDir());
            } catch (java.io.IOException | RuntimeException e) {
                // Migration is additive and may be retried on the next process
                // start; setup must never crash or delay the app/IME UI.
                Log.e(TAG, "Could not migrate settings schema", e);
            }
        }, "settings-migration");
        migration.start();
        ThemePrefs.apply(this);
        DiagnosticLog.event(getFilesDir(), "app_start",
                "process=app,version_code=" + BuildConfig.VERSION_CODE);
    }
}
