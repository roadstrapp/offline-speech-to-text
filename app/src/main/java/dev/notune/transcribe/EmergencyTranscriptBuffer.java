package dev.notune.transcribe;

/** Last-resort process-memory copy used only when private file staging fails. */
final class EmergencyTranscriptBuffer {
    private static String pending;

    private EmergencyTranscriptBuffer() { }

    static synchronized boolean putIfEmpty(String text) {
        if (pending != null || text == null || text.trim().isEmpty()) return false;
        pending = text;
        return true;
    }

    static synchronized String peek() {
        return pending;
    }

    static synchronized void clear() {
        pending = null;
    }
}
