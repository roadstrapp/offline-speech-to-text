package dev.notune.transcribe;

final class NotificationContract {
    static final String CANCEL_START = "cancel_start";
    static final String STOP_DISCARD = "stop_discard";
    static final String STOP_TRANSCRIBE = "stop_transcribe";
    static final String DISCARD = "discard";

    static final class Form {
        final String title;
        final String text;
        final String[] actions;
        Form(String title, String text, String... actions) {
            this.title = title;
            this.text = text;
            this.actions = actions;
        }
    }

    static Form forState(ForegroundRecordingCoordinator.State state) {
        switch (state) {
            case STARTING:
                return new Form("Preparing recording", "Microphone off • cancel to stop setup",
                        CANCEL_START);
            case RECORDING:
                return new Form("Recording speech", "Microphone on • tap an action to finish",
                        STOP_DISCARD, STOP_TRANSCRIBE);
            case TRANSCRIBING:
                return new Form("Transcribing — microphone off", "Processing on device", DISCARD);
            case DISCARDING:
            case STOPPING_FOR_TRANSCRIPTION:
            case FAILED:
                return new Form("Stopping", "Finishing safely");
            default:
                throw new IllegalArgumentException("no active notification for " + state);
        }
    }

    static String labelFor(String action) {
        if (CANCEL_START.equals(action)) return "Cancel start";
        if (STOP_DISCARD.equals(action)) return "Stop and discard";
        if (STOP_TRANSCRIBE.equals(action)) return "Stop and transcribe";
        if (DISCARD.equals(action)) return "Discard";
        throw new IllegalArgumentException("unknown notification action");
    }

    private NotificationContract() { }
}
