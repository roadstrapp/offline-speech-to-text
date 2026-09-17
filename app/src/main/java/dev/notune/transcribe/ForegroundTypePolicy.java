package dev.notune.transcribe;

final class ForegroundTypePolicy {
    static final int UNTYPED = -1;
    static final int NONE = 0;
    static final int MICROPHONE = 1;
    static final int SHORT_SERVICE = 2;

    private ForegroundTypePolicy() { }

    static int captureType(int api) {
        return api < 29 ? UNTYPED : MICROPHONE;
    }

    static int inferenceType(int api) {
        if (api < 29) return UNTYPED;
        if (api < 34) return NONE;
        return SHORT_SERVICE;
    }
}
