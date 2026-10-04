package dev.notune.transcribe;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Finite service equivalence smoke; kernel pipe tests reproduce the drain mechanism.
 * Requires the packaged public benchmark and an already installed supported model.
 * Recognition text stays in memory; no saved transcript or model file is collected. */
final class CallerAudioProviderDeviceTest {
    static void run(Instrumentation fixture) throws Exception {
        byte[] wav;
        try (InputStream input = fixture.getTargetContext().getAssets().open("bench.wav")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            wav = output.toByteArray();
        }
        // Existing fixed mono PCM16/16k benchmark; find its data chunk, not a guessed header size.
        if (wav.length < 44 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F'
                || wav[3] != 'F' || wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V'
                || wav[11] != 'E') throw new AssertionError("invalid benchmark container");
        int offset = 12, data = -1, length = 0;
        boolean formatChecked = false;
        while (offset + 8 <= wav.length) {
            int n = (wav[offset + 4] & 255) | ((wav[offset + 5] & 255) << 8)
                    | ((wav[offset + 6] & 255) << 16) | ((wav[offset + 7] & 255) << 24);
            if (n < 0 || n > wav.length - offset - 8) throw new AssertionError("invalid benchmark WAV");
            if (wav[offset] == 'f' && wav[offset + 1] == 'm'
                    && wav[offset + 2] == 't' && wav[offset + 3] == ' ') {
                int f = offset + 8;
                if (n < 16 || wav[f] != 1 || wav[f + 1] != 0 || wav[f + 2] != 1
                        || wav[f + 3] != 0 || (wav[f + 4] & 255) != 128
                        || (wav[f + 5] & 255) != 62 || wav[f + 6] != 0 || wav[f + 7] != 0
                        || wav[f + 12] != 2 || wav[f + 13] != 0
                        || wav[f + 14] != 16 || wav[f + 15] != 0)
                    throw new AssertionError("benchmark is not PCM16 mono 16k");
                formatChecked = true;
            }
            if (wav[offset] == 'd' && wav[offset + 1] == 'a'
                    && wav[offset + 2] == 't' && wav[offset + 3] == 'a') {
                data = offset + 8; length = n; break;
            }
            offset += 8 + n + (n & 1);
        }
        if (!formatChecked || data < 0 || length != 352000) throw new AssertionError("unexpected benchmark PCM");
        byte[] pcm = java.util.Arrays.copyOfRange(wav, data, data + length);
        String eof = recognize(fixture, pcm, false, 0);
        String stop = recognize(fixture, pcm, true, 0);
        if (!eof.equals(stop)) throw new AssertionError("Stop differed from complete EOF benchmark");
        recognize(fixture, new byte[] {1, 2, 3}, false, SpeechRecognizer.ERROR_AUDIO);
        recognize(fixture, new byte[320], false, SpeechRecognizer.ERROR_NO_MATCH);
    }

    private static void closeReader(ParcelFileDescriptor reader) {
        try { reader.close(); } catch (java.io.IOException ignored) {}
    }

    private static String recognize(Instrumentation fixture, byte[] pcm, boolean stop, int expectedError)
            throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean feederFailed = new java.util.concurrent.atomic.AtomicBoolean();
        SpeechRecognizer[] recognizer = new SpeechRecognizer[1];
        String[] text = new String[1];
        int[] error = new int[1];
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        OutputStream writer = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
        try {
            fixture.runOnMainSync(() -> {
                recognizer[0] = SpeechRecognizer.createSpeechRecognizer(fixture.getTargetContext(),
                        new ComponentName(fixture.getTargetContext(), VoiceRecognitionService.class));
                recognizer[0].setRecognitionListener(new RecognitionListener() {
                    public void onReadyForSpeech(Bundle b) {
                        // The service has acquired its own read descriptor. Do not retain a
                        // local reader that would mask service closure and strand the writer.
                        closeReader(pipe[0]); ready.countDown();
                    }
                    public void onBeginningOfSpeech() {}
                    public void onRmsChanged(float f) {}
                    public void onBufferReceived(byte[] b) {}
                    public void onEndOfSpeech() {}
                    public void onPartialResults(Bundle b) {}
                    public void onEvent(int i, Bundle b) {}
                    public void onError(int code) {
                        error[0] = code; closeReader(pipe[0]);
                        ready.countDown(); terminal.countDown();
                    }
                    public void onResults(Bundle result) {
                        ArrayList<String> hypotheses = result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        text[0] = hypotheses == null || hypotheses.isEmpty() ? "" : hypotheses.get(0);
                        terminal.countDown();
                    }
                });
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                recognizer[0].startListening(intent);
            });
            // A separate producer bounds a stalled service independently of the fixture thread.
            Thread feeder = new Thread(() -> {
                try {
                    if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("provider not ready");
                    if (terminal.getCount() == 0) return;
                    writer.write(pcm); writer.flush();
                    if (stop) fixture.runOnMainSync(() -> recognizer[0].stopListening());
                    else writer.close();
                } catch (Throwable failure) {
                    feederFailed.set(true); terminal.countDown();
                }
            }, "caller-audio-device-fixture");
            feeder.start();
            if (!terminal.await(45, TimeUnit.SECONDS)) throw new AssertionError("caller service timed out");
            writer.close();
            feeder.join(5000);
            if (feeder.isAlive()) throw new AssertionError("caller feeder did not stop; provider error=" + error[0]);
            if (error[0] != expectedError) throw new AssertionError("caller error=" + error[0]
                    + " expected=" + expectedError);
            if (expectedError == 0 && feederFailed.get()) throw new AssertionError("caller feeder failed");
            if (expectedError == 0 && (text[0] == null || text[0].trim().isEmpty()))
                throw new AssertionError("benchmark produced no text");
            return text[0];
        } finally {
            try { writer.close(); } catch (Exception ignored) {}
            try { pipe[0].close(); } catch (Exception ignored) {}
            fixture.runOnMainSync(() -> { if (recognizer[0] != null) recognizer[0].destroy(); });
        }
    }
}
