package dev.notune.transcribe;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic caller/mic concurrency; transcript text remains in memory. */
final class RecognitionConcurrencyDeviceTest {
    private static final class Outcome implements RecognitionListener {
        final CountDownLatch ready = new CountDownLatch(1), terminal = new CountDownLatch(1);
        final AtomicInteger results = new AtomicInteger(), errors = new AtomicInteger();
        volatile int error;
        public void onReadyForSpeech(Bundle b) { ready.countDown(); }
        public void onBeginningOfSpeech() { }
        public void onRmsChanged(float value) { }
        public void onBufferReceived(byte[] b) { }
        public void onEndOfSpeech() { }
        public void onPartialResults(Bundle b) { }
        public void onEvent(int i, Bundle b) { }
        public void onError(int code) { error = code; errors.incrementAndGet(); terminal.countDown(); }
        public void onResults(Bundle b) { results.incrementAndGet(); terminal.countDown(); }
    }
    static void run(Instrumentation fixture, byte[] pcm) throws Exception {
        SpeechRecognizer[] recognizers = new SpeechRecognizer[3];
        Outcome first = new Outcome(), microphone = new Outcome(), second = new Outcome();
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] refused = ParcelFileDescriptor.createPipe();
        OutputStream writer = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
        Thread feeder = null;
        android.app.Activity activity = fixture.startActivitySync(new Intent(fixture.getTargetContext(),
                MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            fixture.runOnMainSync(() -> {
                for (int i = 0; i < recognizers.length; i++) recognizers[i] = SpeechRecognizer.createSpeechRecognizer(
                        fixture.getTargetContext(), new ComponentName(fixture.getTargetContext(), VoiceRecognitionService.class));
                recognizers[0].setRecognitionListener(first);
                recognizers[1].setRecognitionListener(microphone);
                recognizers[2].setRecognitionListener(second);
                recognizers[0].startListening(audio(pipe[0]));
            });
            check(first.ready.await(10, TimeUnit.SECONDS), "caller not ready");
            pipe[0].close();
            feeder = new Thread(() -> { try { writer.write(pcm); writer.flush(); } catch (Exception e) { throw new AssertionError(e); } }, "concurrency-PCM");
            feeder.start();
            long start = SystemClock.elapsedRealtime();
            fixture.runOnMainSync(() -> recognizers[1].startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)));
            check(SystemClock.elapsedRealtime() - start < 2000, "mic entry blocked main thread");
            check(microphone.ready.await(10, TimeUnit.SECONDS), "mic not admitted beside caller, error=" + microphone.error);
            fixture.runOnMainSync(() -> recognizers[2].startListening(audio(refused[0])));
            check(second.terminal.await(10, TimeUnit.SECONDS), "second caller got no terminal");
            check(second.error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && second.errors.get() == 1, "second caller not exactly one BUSY");
            fixture.runOnMainSync(recognizers[1]::cancel);
            feeder.join(10000); check(!feeder.isAlive(), "PCM feeder blocked");
            writer.close();
            check(first.terminal.await(90, TimeUnit.SECONDS), "original caller did not finish");
            check(first.results.get() == 1 && first.errors.get() == 0, "original caller replaced/failed");
            SystemClock.sleep(250);
            check(microphone.results.get() == 0 && microphone.errors.get() == 0, "mic callback after own cancel");
        } finally {
            writer.close(); pipe[0].close(); refused[0].close(); refused[1].close();
            if (feeder != null) feeder.join(5000);
            fixture.runOnMainSync(() -> { for (SpeechRecognizer recognizer : recognizers) if (recognizer != null) recognizer.destroy(); activity.finish(); });
        }
    }
    private static Intent audio(ParcelFileDescriptor source) {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
