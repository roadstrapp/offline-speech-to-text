package dev.notune.transcribe;

/**
 * Process-retained owner for one manual model import.
 *
 * <p>The lifecycle stores only immutable progress metadata, never an Activity or content URI.
 * A recreated ModelsActivity attaches a new observer and receives the latest snapshot.</p>
 */
final class ModelImportLifecycle {
    enum State { IDLE, RUNNING, COMPLETE, FAILED }

    static final class Snapshot {
        final State state;
        final String requestedName;
        final long copied;
        final long total;
        final String installedName;
        final ModelImportTask.Failure failure;

        Snapshot(State state, String requestedName, long copied, long total,
                 String installedName, ModelImportTask.Failure failure) {
            this.state = state;
            this.requestedName = requestedName;
            this.copied = copied;
            this.total = total;
            this.installedName = installedName;
            this.failure = failure;
        }
    }

    interface Observer {
        void onImportState(Snapshot snapshot);
    }

    interface Starter {
        void start(ModelImportTask.Listener listener);
    }

    private Observer observer;
    private Snapshot snapshot = idleSnapshot();

    synchronized void attach(Observer nextObserver) {
        observer = nextObserver;
        nextObserver.onImportState(snapshot);
    }

    synchronized void detach(Observer currentObserver) {
        if (observer == currentObserver) observer = null;
    }

    synchronized boolean start(String requestedName, long total, Starter starter) {
        if (snapshot.state == State.RUNNING) return false;
        snapshot = new Snapshot(State.RUNNING, requestedName, 0L, total, null, null);
        notifyObserver();
        try {
            starter.start(new ModelImportTask.Listener() {
                @Override public void onProgress(long copied, long reportedTotal) {
                    synchronized (ModelImportLifecycle.this) {
                        snapshot = new Snapshot(State.RUNNING, requestedName, copied,
                                reportedTotal, null, null);
                        notifyObserver();
                    }
                }

                @Override public void onComplete(String installedName) {
                    synchronized (ModelImportLifecycle.this) {
                        snapshot = new Snapshot(State.COMPLETE, requestedName, 0L, total,
                                installedName, null);
                        notifyObserver();
                    }
                }

                @Override public void onFailure(ModelImportTask.Failure failure) {
                    synchronized (ModelImportLifecycle.this) {
                        snapshot = new Snapshot(State.FAILED, requestedName, 0L, total,
                                null, failure);
                        notifyObserver();
                    }
                }
            });
            return true;
        } catch (RuntimeException error) {
            snapshot = new Snapshot(State.FAILED, requestedName, 0L, total, null,
                    ModelImportTask.Failure.IMPORT_FAILED);
            notifyObserver();
            return false;
        }
    }

    synchronized boolean isRunning() {
        return snapshot.state == State.RUNNING;
    }

    synchronized Snapshot snapshot() {
        return snapshot;
    }

    synchronized void clearTerminal() {
        if (snapshot.state == State.COMPLETE || snapshot.state == State.FAILED) {
            snapshot = idleSnapshot();
        }
    }

    private static Snapshot idleSnapshot() {
        return new Snapshot(State.IDLE, null, 0L, 0L, null, null);
    }

    private void notifyObserver() {
        if (observer != null) observer.onImportState(snapshot);
    }
}
