package dev.notune.transcribe;

import android.app.Application;

/**
 * Applies the saved dark-mode choice before any activity is created. Runs in
 * every process (including the ":ime" keyboard process). Dynamic color is not
 * applied because it would replace the app's true-black AMOLED night palette.
 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ThemePrefs.apply(this);
    }
}
