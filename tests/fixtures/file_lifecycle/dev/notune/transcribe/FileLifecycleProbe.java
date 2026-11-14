package dev.notune.transcribe;

public class FileLifecycleProbe {
    static class Listener implements FileTranscription.Listener {
        volatile int results, failures;
        volatile FileTranscriptionContract.Failure failure;
        public void onStatus(String s) { }
        public void onReady() { }
        public void onPartial(String t) { }
        public void onProgress(long a, long b) { }
        public void onDecodeComplete() { }
        public void onResult(String t, long a, long b) { results++; }
        public void onFailure(FileTranscriptionContract.Failure f) { failure = f; failures++; }
    }
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static FileTranscription job(Listener listener) {
        return new FileTranscription(new android.content.Context(), null, false, listener);
    }
    public static void main(String[] args) throws Exception {
        Listener l = new Listener();
        FileTranscription f = job(l); f.start();
        long until = System.nanoTime() + 2_000_000_000L;
        while (l.results == 0 && l.failures == 0 && System.nanoTime() < until) Thread.sleep(5);
        check(l.results == 1 && l.failures == 0 && FileTranscription.released == 1,
                "Ready before handle return lost or leaked");
        FileTranscription.cancelInCreate = true;
        FileTranscription.released = 0;
        l = new Listener(); f = job(l); f.start();
        check(l.results == 0 && l.failures == 0 && FileTranscription.released == 1,
                "cancel during create leaks or delivers");
        FileTranscription.cancelInCreate = false;
        for (long outcome : new long[] {-1, 0, -2}) {
            FileTranscription.createResult = outcome; FileTranscription.released = 0;
            l = new Listener(); f = job(l); f.start(); f.start(); f.cancel();
            check(l.results == 0 && l.failures == 1, "creation failure not exactly once");
            check(l.failure == (outcome == -1 ? FileTranscriptionContract.Failure.BUSY
                    : FileTranscriptionContract.Failure.ENGINE), "wrong admission failure");
            check(FileTranscription.released == 0, "failure code released as handle");
        }
        int before = FileTranscription.creates;
        l = new Listener(); f = job(l); f.cancel(); f.start();
        check(FileTranscription.creates == before && l.failures == 0 && l.results == 0,
                "cancelled-before-start job entered native code");
        System.out.println("PASS actual Java lifecycle: synchronous Ready, cancel handoff, busy, "
                + "creation failures, exactly-once failure and cancel-before-start");
    }
}
