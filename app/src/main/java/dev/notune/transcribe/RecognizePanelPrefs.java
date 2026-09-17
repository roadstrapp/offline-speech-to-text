package dev.notune.transcribe;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Persistent placement for the system voice-input panel. */
final class RecognizePanelPrefs {
    static final int BOTTOM = 0;
    static final int RIGHT = 1;
    static final int LEFT = 2;

    private static final String FILE_NAME = "recognize_panel_side";

    private RecognizePanelPrefs() {}

    static int get(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.isFile()) return BOTTOM;
        try {
            String value = new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8).trim();
            if ("right".equals(value)) return RIGHT;
            if ("left".equals(value)) return LEFT;
        } catch (IOException ignored) { }
        return BOTTOM;
    }

    static void set(Context context, int position) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (position == BOTTOM) {
            file.delete();
            return;
        }
        String value = position == LEFT ? "left" : "right";
        try {
            Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }
}
