package dev.notune.transcribe;

import dev.notune.transcribe.IForegroundRecordingObserver;

interface IForegroundRecordingService {
    int getState();
    long getGeneration();
    boolean matchesSession(String token, long generation, String notificationNonce);
    boolean performAction(int action, long generation);
    void registerObserver(IForegroundRecordingObserver observer);
    void unregisterObserver(IForegroundRecordingObserver observer);
}
