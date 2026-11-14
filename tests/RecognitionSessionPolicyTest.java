package dev.notune.transcribe;

import dev.notune.transcribe.RecognitionSessionPolicy.Action;

public final class RecognitionSessionPolicyTest {
    public static void main(String[] args) {
        // Caller audio / files never touch the microphone slot, in either direction.
        check(RecognitionSessionPolicy.admit(true, false, true) == Action.START_BACKGROUND,
                "background request blocked by a microphone request");
        check(RecognitionSessionPolicy.admit(true, false, false) == Action.START_BACKGROUND,
                "background request not started");
        check(RecognitionSessionPolicy.admit(false, true, false) == Action.START_MICROPHONE,
                "microphone request blocked by a caller-audio job");
        // One background job at a time: the new one is refused, the running one kept.
        check(RecognitionSessionPolicy.admit(true, true, false) == Action.REFUSE_BUSY,
                "second background job admitted");
        check(RecognitionSessionPolicy.admit(true, true, true) == Action.REFUSE_BUSY,
                "second background job admitted beside a microphone request");
        // A newer microphone request replaces the previous one, which is told.
        check(RecognitionSessionPolicy.admit(false, false, true) == Action.REPLACE_MICROPHONE,
                "previous microphone request dropped silently");
        check(RecognitionSessionPolicy.admit(false, true, true) == Action.REPLACE_MICROPHONE,
                "microphone replacement depends on the background slot");
        // The replaced and the refused caller hear the same busy code (SpeechRecognizer values).
        check(RecognitionSessionPolicy.ERROR_RECOGNIZER_BUSY == 8, "busy code");
        check(RecognitionSessionPolicy.destroyedError(31) == 11, "API 31 destroy code");
        check(RecognitionSessionPolicy.destroyedError(30) == 4, "pre-31 destroy code");
        System.out.println("RecognitionSessionPolicyTest passed");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
