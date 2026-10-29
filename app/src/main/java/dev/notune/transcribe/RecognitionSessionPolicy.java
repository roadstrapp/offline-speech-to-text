package dev.notune.transcribe;

/**
 * Android-independent admission rules for {@link VoiceRecognitionService}.
 *
 * The service has two slots: one microphone request, and one request that
 * opens no microphone (caller-supplied audio, or a file). They never affect
 * each other. Every request that does not end with a result ends with exactly
 * one error, including a request that is refused or replaced: a newer
 * microphone request replaces the previous one, and the replaced caller hears
 * {@link #ERROR_RECOGNIZER_BUSY} first, the same code a refused request gets.
 * Integers mirror {@code android.speech.SpeechRecognizer} so this runs in host
 * tests.
 */
final class RecognitionSessionPolicy {
    static final int ERROR_SERVER = 4;
    static final int ERROR_RECOGNIZER_BUSY = 8;
    static final int ERROR_SERVER_DISCONNECTED = 11;
    static final int API_SERVER_DISCONNECTED = 31;

    enum Action {
        /** Start in the free non-microphone slot. */
        START_BACKGROUND,
        /** Refuse with {@link #ERROR_RECOGNIZER_BUSY}; the running job is untouched. */
        REFUSE_BUSY,
        /** Start in the free microphone slot. */
        START_MICROPHONE,
        /** Send {@link #ERROR_RECOGNIZER_BUSY} to the previous microphone request, then start. */
        REPLACE_MICROPHONE,
    }

    private RecognitionSessionPolicy() {}

    /**
     * @param background the request supplies its own audio (caller audio or a file)
     * @param backgroundBusy the non-microphone slot holds a live request
     * @param microphoneBusy the microphone slot holds a live request other than this one
     */
    static Action admit(boolean background, boolean backgroundBusy, boolean microphoneBusy) {
        if (background) return backgroundBusy ? Action.REFUSE_BUSY : Action.START_BACKGROUND;
        return microphoneBusy ? Action.REPLACE_MICROPHONE : Action.START_MICROPHONE;
    }

    /** Error for a live request when the service itself goes away. */
    static int destroyedError(int sdkInt) {
        return sdkInt >= API_SERVER_DISCONNECTED ? ERROR_SERVER_DISCONNECTED : ERROR_SERVER;
    }
}
