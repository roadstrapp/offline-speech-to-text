package dev.notune.transcribe;

/** Pure setup-state decisions shared by the Android UI and focused tests. */
final class SetupState {
    enum Microphone {
        NOT_REQUESTED,
        DENIED,
        DENIED_PERMANENTLY,
        GRANTED
    }

    enum Ime {
        DISABLED,
        ENABLED,
        SELECTED
    }

    private SetupState() {}

    static Microphone microphone(boolean granted, boolean requestedBefore,
                                 boolean shouldShowRationale) {
        if (granted) return Microphone.GRANTED;
        if (!requestedBefore) return Microphone.NOT_REQUESTED;
        return shouldShowRationale ? Microphone.DENIED : Microphone.DENIED_PERMANENTLY;
    }

    static Ime ime(boolean enabled, boolean selected) {
        if (selected) return Ime.SELECTED;
        return enabled ? Ime.ENABLED : Ime.DISABLED;
    }
}
