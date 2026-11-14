package dev.notune.transcribe;

import android.app.Instrumentation;
import java.lang.reflect.Method;

/** Real JNI admission and slot reuse, without opening a microphone or decoding a file. */
final class FileAdmissionDeviceTest {
    static final class Listener implements FileTranscription.Listener {
        int failures;
        FileTranscriptionContract.Failure failure;
        public void onStatus(String s) { }
        public void onReady() { }
        public void onPartial(String s) { }
        public void onProgress(long samples, long duration) { }
        public void onDecodeComplete() { }
        public void onResult(String text, long audio, long compute) {
            throw new AssertionError("Admission probe must not transcribe");
        }
        public void onFailure(FileTranscriptionContract.Failure value) {
            failure = value; failures++;
        }
    }

    static void run(Instrumentation fixture) throws Exception {
        if (!"app.offlinespeechtotext.validation".equals(
                fixture.getTargetContext().getPackageName())) throw new AssertionError("validation only");
        Method create = FileTranscription.class.getDeclaredMethod("nativeCreate",
                FileTranscription.class, boolean.class);
        Method release = FileTranscription.class.getDeclaredMethod("nativeRelease", long.class);
        create.setAccessible(true); release.setAccessible(true);
        long[] handles = new long[2];
        FileTranscription[] owners = new FileTranscription[2];
        try {
            for (int i = 0; i < 2; i++) {
                // Native owners are deliberately not started: Ready cannot start a decoder
                // until the Java owner accepts the returned handle. The slots stay occupied.
                owners[i] = new FileTranscription(fixture.getTargetContext(), null, false,
                        new Listener());
                handles[i] = (Long) create.invoke(null, owners[i], false);
                if (handles[i] <= 0) throw new AssertionError("Initial slot unavailable");
            }
            Listener overloaded = new Listener();
            FileTranscription third = new FileTranscription(fixture.getTargetContext(), null,
                    false, overloaded);
            third.start(); third.start(); third.cancel();
            if (overloaded.failures != 1 || overloaded.failure != FileTranscriptionContract.Failure.BUSY)
                throw new AssertionError("Native cap did not produce one BUSY failure");
            release.invoke(null, handles[0]); handles[0] = 0;
            handles[0] = (Long) create.invoke(null, owners[0], false);
            if (handles[0] <= 0) throw new AssertionError("Released slot was not reusable");
        } finally {
            for (long handle : handles) if (handle > 0) release.invoke(null, handle);
            for (FileTranscription owner : owners) if (owner != null) owner.cancel();
        }
    }
}
