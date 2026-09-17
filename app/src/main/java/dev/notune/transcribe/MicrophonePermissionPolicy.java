package dev.notune.transcribe;

/** Pure policy for interpreting Android's raw microphone app-op mode. */
final class MicrophonePermissionPolicy {
    static final int MODE_ALLOWED = 0;
    static final int MODE_IGNORED = 1;
    static final int MODE_ERRORED = 2;
    static final int MODE_FOREGROUND = 4;

    private MicrophonePermissionPolicy() { }

    static boolean allowsVisibleStart(
            boolean runtimePermissionGranted,
            int appOpMode,
            boolean visibleActivityResumed) {
        if (!runtimePermissionGranted || !visibleActivityResumed) return false;
        return appOpMode == MODE_ALLOWED || appOpMode == MODE_FOREGROUND;
    }
}
