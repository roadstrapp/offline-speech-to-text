package dev.notune.transcribe;

oneway interface IForegroundRecordingObserver {
    void onStateChanged(int state, long generation);
}
