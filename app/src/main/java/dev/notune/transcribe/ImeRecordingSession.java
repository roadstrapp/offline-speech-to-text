package dev.notune.transcribe;

/**
 * Android-independent recording target policy. InputConnection is a replaceable
 * IPC wrapper, so a transcript belongs to the IME lifecycle generation that
 * started it rather than one Java object's identity. Discard remains latched
 * until a new recording begins.
 */
final class ImeRecordingSession {
    private Object origin;
    private long originGeneration = -1L;
    private boolean discard;

    void begin(Object origin, long editorGeneration) {
        this.origin = origin;
        originGeneration = editorGeneration;
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

    boolean canCommitTo(Object current, long editorGeneration,
                        boolean editorSafe, boolean inputActive) {
        return !discard && origin != null && current != null
                && editorGeneration == originGeneration && editorSafe && inputActive;
    }

    boolean isDiscarded() {
        return discard;
    }

    void finish() {
        origin = null;
        originGeneration = -1L;
        discard = false;
    }
}
