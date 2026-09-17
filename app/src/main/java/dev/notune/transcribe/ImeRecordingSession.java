package dev.notune.transcribe;

/**
 * Android-independent recording target policy. A transcript belongs only to the
 * exact editor connection that started its recording, and discard remains
 * latched until a new recording begins.
 */
final class ImeRecordingSession {
    private Object origin;
    private boolean discard;

    void begin(Object origin) {
        this.origin = origin;
        discard = false;
    }

    Object origin() {
        return origin;
    }

    void discard() {
        discard = true;
    }

    void onError() {
        discard = true;
    }

    boolean canCommitTo(Object current, boolean editorSafe, boolean inputActive) {
        return !discard && origin != null && current == origin && editorSafe && inputActive;
    }

    boolean isDiscarded() {
        return discard;
    }

    void finish() {
        origin = null;
        discard = false;
    }
}
