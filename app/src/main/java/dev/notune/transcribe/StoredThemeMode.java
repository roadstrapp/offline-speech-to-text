package dev.notune.transcribe;

/** Resolves the persisted AppCompat night-mode integer without Android I/O. */
final class StoredThemeMode {
    static final int FOLLOW_SYSTEM = -1;
    static final int LIGHT = 1;
    static final int DARK = 2;

    private StoredThemeMode() {}

    static int resolve(String persisted) {
        if (persisted != null) {
            try {
                int mode = Integer.parseInt(persisted.trim());
                if (mode == FOLLOW_SYSTEM || mode == LIGHT || mode == DARK) {
                    return mode;
                }
            } catch (NumberFormatException ignored) {
                // Missing or corrupt state uses the product default below.
            }
        }
        return DARK;
    }
}
