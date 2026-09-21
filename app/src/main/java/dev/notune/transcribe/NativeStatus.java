package dev.notune.transcribe;

/** Shared interpretation of the native engine's human-readable status callback. */
final class NativeStatus {
    enum Phase { ERROR, LOADING, PROCESSING, WAITING, READY, UNKNOWN }

    private NativeStatus() {}

    static boolean isError(String status) {
        return status != null && status.startsWith("Error");
    }

    static boolean isLoading(String status) {
        return status != null
                && (status.contains("Loading") || status.contains("Initializing"));
    }

    static boolean isProcessing(String status) {
        return status != null
                && (status.contains("Transcribing") || status.contains("Processing"));
    }

    static boolean isWaiting(String status) {
        return status != null && status.contains("Waiting");
    }

    static Phase classify(String status) {
        if (status == null) return Phase.UNKNOWN;
        if (isError(status)) return Phase.ERROR;
        if (isLoading(status)) return Phase.LOADING;
        if (isProcessing(status)) return Phase.PROCESSING;
        if (isWaiting(status)) return Phase.WAITING;
        if (status.equals("Ready")) return Phase.READY;
        return Phase.UNKNOWN;
    }

    static String diagnosticLabel(String status) {
        switch (classify(status)) {
            case ERROR: return "error";
            case LOADING: return "loading";
            case PROCESSING: return "processing";
            case WAITING: return "idle";
            case READY: return "ready";
            case UNKNOWN:
            default: return "unknown";
        }
    }
}
