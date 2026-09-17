package dev.notune.transcribe;

/** Keeps stale or malformed start commands from stopping the live session owner. */
final class ForegroundServiceStopPolicy {
    private ForegroundServiceStopPolicy() { }

    static boolean shouldStop(ForegroundRecordingCoordinator coordinator) {
        return coordinator.session() == null;
    }
}
