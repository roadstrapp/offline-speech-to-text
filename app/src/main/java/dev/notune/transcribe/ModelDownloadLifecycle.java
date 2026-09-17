package dev.notune.transcribe;

/**
 * Process-retained owner for one explicit user-started model download.
 *
 * <p>The owner keeps task state and the cancellation handle but never keeps an Activity. A recreated
 * Activity attaches a fresh observer and immediately receives the latest snapshot.</p>
 */
final class ModelDownloadLifecycle {
    enum State { IDLE, RUNNING, COMPLETE, FAILED }

    static final class Snapshot {
        final State state;
        final long downloaded;
        final long total;
        final String modelId;
        final String origin;
        final String installedName;
        final String failure;

        Snapshot(State state, long downloaded, long total, String modelId, String origin,
                 String installedName, String failure) {
            this.state = state;
            this.downloaded = downloaded;
            this.total = total;
            this.modelId = modelId;
            this.origin = origin;
            this.installedName = installedName;
            this.failure = failure;
        }
    }

    interface Observer {
        void onDownloadState(Snapshot snapshot);
    }

    interface Starter {
        ModelDownloadTask.Handle start(ModelDownloadTask.Listener listener);
    }

    private Observer observer;
    private ModelDownloadTask.Handle handle;
    private boolean failureAnnouncementPending;
    private Snapshot snapshot = new Snapshot(State.IDLE, 0L, 0L,
            null, null, null, null);

    synchronized void attach(Observer nextObserver) {
        observer = nextObserver;
        nextObserver.onDownloadState(snapshot);
    }

    synchronized void detach(Observer currentObserver) {
        if (observer == currentObserver) observer = null;
    }

    synchronized boolean start(String modelId, String origin, Starter starter) {
        if (snapshot.state == State.RUNNING) return false;
        failureAnnouncementPending = false;
        snapshot = new Snapshot(State.RUNNING, 0L, 0L, modelId, origin, null, null);
        notifyObserver();
        try {
            handle = starter.start(new ModelDownloadTask.Listener() {
                @Override public void onStarted(ModelDownloadTask.Handle startedHandle) {
                    synchronized (ModelDownloadLifecycle.this) {
                        handle = startedHandle;
                    }
                }

                @Override public void onProgress(long downloaded, long total) {
                    synchronized (ModelDownloadLifecycle.this) {
                        snapshot = new Snapshot(
                                State.RUNNING, downloaded, total, modelId, origin, null, null);
                        notifyObserver();
                    }
                }

                @Override public void onComplete(String installedName) {
                    synchronized (ModelDownloadLifecycle.this) {
                        handle = null;
                        snapshot = new Snapshot(
                                State.COMPLETE, 0L, 0L, modelId, origin, installedName, null);
                        notifyObserver();
                    }
                }

                @Override public void onFailure(String reason) {
                    synchronized (ModelDownloadLifecycle.this) {
                        handle = null;
                        failureAnnouncementPending = true;
                        snapshot = new Snapshot(
                                State.FAILED, 0L, 0L, modelId, origin, null, reason);
                        notifyObserver();
                    }
                }
            });
            return true;
        } catch (RuntimeException error) {
            handle = null;
            failureAnnouncementPending = true;
            snapshot = new Snapshot(
                    State.FAILED, 0L, 0L, modelId, origin, null,
                    "Unable to start the model download.");
            notifyObserver();
            return false;
        }
    }

    synchronized boolean isRunning() {
        return snapshot.state == State.RUNNING;
    }

    synchronized void cancel() {
        if (handle != null) handle.cancel();
    }

    synchronized void clearTerminal() {
        if (snapshot.state == State.COMPLETE || snapshot.state == State.FAILED) {
            failureAnnouncementPending = false;
            snapshot = new Snapshot(State.IDLE, 0L, 0L,
                    null, null, null, null);
        }
    }

    synchronized boolean consumeFailureAnnouncement() {
        if (!failureAnnouncementPending) return false;
        failureAnnouncementPending = false;
        return true;
    }

    synchronized Snapshot snapshot() {
        return snapshot;
    }

    static boolean shouldCancelOnDestroy(boolean changingConfigurations) {
        return !changingConfigurations;
    }

    private void notifyObserver() {
        if (observer != null) observer.onDownloadState(snapshot);
    }
}
