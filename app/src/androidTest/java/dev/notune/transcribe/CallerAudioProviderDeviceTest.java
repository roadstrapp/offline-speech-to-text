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
    static void run(Instrumentation fixture) throws Exception { run(fixture, true); }

    static void runWithoutMicrophone(Instrumentation fixture) throws Exception { run(fixture, false); }

    private static void run(Instrumentation fixture, boolean includeMicrophone) throws Exception {
        byte[] pcm = benchmarkPcm(fixture);
        if (includeMicrophone) RecognitionConcurrencyDeviceTest.run(fixture, pcm);
        // Keep one service connection across cases. Destroying and immediately
        // recreating recognizers races Android's asynchronous service unbind.
        SpeechRecognizer[] recognizer = new SpeechRecognizer[1];
        fixture.runOnMainSync(() -> recognizer[0] = SpeechRecognizer.createSpeechRecognizer(
                fixture.getTargetContext(), new ComponentName(fixture.getTargetContext(),
                        VoiceRecognitionService.class)));
        try {
            String eof = recognize(fixture, recognizer[0], pcm, false, 0);
            String stop = recognize(fixture, recognizer[0], pcm, true, 0);
            if (!eof.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z ]", "").trim().endsWith("for your country"))
                throw new AssertionError("caller benchmark lost its tail");
            if (!eof.equals(stop)) throw new AssertionError("Stop differed from complete EOF benchmark");
            recognize(fixture, recognizer[0], new byte[] {1, 2, 3}, false, SpeechRecognizer.ERROR_AUDIO);
            recognize(fixture, recognizer[0], new byte[320], false, SpeechRecognizer.ERROR_NO_MATCH);
        } finally {
            fixture.runOnMainSync(() -> recognizer[0].destroy());
            fixture.waitForIdleSync();
        }
    }

    /** A caller that records into its own buffer first: the descriptor stays open and
     * silent for 30 s, then the benchmark arrives and the writer closes. The request must
     * stay open through the silence and end with exactly one complete result. Returns
     * the wall time from ready to first write, for the caller's timing assertion. */
    static long runLateFirstWrite(Instrumentation fixture) throws Exception {
        byte[] pcm = benchmarkPcm(fixture);
        SpeechRecognizer[] recognizer = new SpeechRecognizer[1];
        CountDownLatch ready = new CountDownLatch(1), terminal = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger results = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
        String[] text = new String[1];
        int[] error = new int[1];
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        OutputStream writer = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
        Thread feeder = null;
        try {
            fixture.runOnMainSync(() -> {
                recognizer[0] = SpeechRecognizer.createSpeechRecognizer(fixture.getTargetContext(),
                        new ComponentName(fixture.getTargetContext(), VoiceRecognitionService.class));
                recognizer[0].setRecognitionListener(new RecognitionListener() {
                    public void onReadyForSpeech(Bundle b) { closeReader(pipe[0]); ready.countDown(); }
                    public void onBeginningOfSpeech() {}
                    public void onRmsChanged(float f) {}
                    public void onBufferReceived(byte[] b) {}
                    public void onEndOfSpeech() {}
                    public void onPartialResults(Bundle b) {}
                    public void onEvent(int i, Bundle b) {}
                    public void onError(int code) {
                        error[0] = code; errors.incrementAndGet(); closeReader(pipe[0]);
                        ready.countDown(); terminal.countDown();
                    }
                    public void onResults(Bundle result) {
                        ArrayList<String> hypotheses = result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        text[0] = hypotheses == null || hypotheses.isEmpty() ? "" : hypotheses.get(0);
                        results.incrementAndGet(); terminal.countDown();
                    }
                });
                recognizer[0].startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT));
            });
            if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("provider not ready");
            long readyAt = android.os.SystemClock.elapsedRealtime();
            if (terminal.await(30, TimeUnit.SECONDS))
                throw new AssertionError("caller ended during silence, error=" + error[0]);
            long silentMs = android.os.SystemClock.elapsedRealtime() - readyAt;
            boolean[] feederFailed = new boolean[1];
            feeder = new Thread(() -> {
                try { writer.write(pcm); writer.close(); }
                catch (Throwable failure) { feederFailed[0] = true; terminal.countDown(); }
            }, "caller-audio-late-fixture");
            feeder.start();
            // The 60 s session cap counts from start; input ends about 31 s in.
            if (!terminal.await(28, TimeUnit.SECONDS)) throw new AssertionError("caller service timed out");
            feeder.join(5000);
            if (feeder.isAlive() || feederFailed[0])
                throw new AssertionError("caller feeder failed; provider error=" + error[0]);
            android.os.SystemClock.sleep(1000);
            if (results.get() != 1 || errors.get() != 0)
                throw new AssertionError("results=" + results.get() + " errors=" + errors.get() + " error=" + error[0]);
            String words = text[0] == null ? "" : text[0].toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z ]", "").replaceAll("\\s+", " ").trim();
            // bench.wav: "and so my fellow americans ask not ... what you can do for your country".
            if (!words.startsWith("and so my fellow americans") && !words.startsWith("so my fellow americans"))
                throw new AssertionError("late caller benchmark lost its start: " + words);
            if (!words.endsWith("for your country"))
                throw new AssertionError("late caller benchmark lost its tail: " + words);
            return silentMs;
        } finally {
            try { writer.close(); } catch (Exception ignored) {}
            try { pipe[0].close(); } catch (Exception ignored) {}
            if (feeder != null) feeder.join(5000);
            fixture.runOnMainSync(() -> { if (recognizer[0] != null) recognizer[0].destroy(); });
            fixture.waitForIdleSync();
        }
    }

    /** Clip-length regression for the encoder padding defect fixed in transcribe.cpp
     * 0.4.2: one mel frame is 160 samples, so eight consecutive cuts cover every
     * frame count modulo the encoder's 8x subsampling. Speech is cut mid-word, so
     * endpoint trimming keeps every case's full length. The 21.12 s base repeats the
     * benchmark to cross Parakeet's 20.5 s full-attention limit. A case whose word
     * count falls well below its share of the complete transcript counts as truncated.
     * Only counts and timings leave. */
    static Bundle runLengthSweep(Instrumentation fixture) throws Exception {
        byte[] single = benchmarkPcm(fixture);
        byte[] pcm = java.util.Arrays.copyOf(single, single.length * 2);
        System.arraycopy(single, 0, pcm, single.length, single.length);
        // 5.04 s and 10.56 s as in the upstream report, then past the full-attention limit.
        int[] baseSamples = {80_640, 168_960, 337_920};
        int cases = 0, empty = 0, truncated = 0;
        long slowestMs = 0;
        StringBuilder emptyLengths = new StringBuilder();
        StringBuilder truncatedLengths = new StringBuilder();
        StringBuilder timings = new StringBuilder();
        SpeechRecognizer[] recognizer = new SpeechRecognizer[1];
        fixture.runOnMainSync(() -> recognizer[0] = SpeechRecognizer.createSpeechRecognizer(
                fixture.getTargetContext(), new ComponentName(fixture.getTargetContext(),
                        VoiceRecognitionService.class)));
        try {
            Outcome complete = attempt(fixture, recognizer[0], single, false);
            int completeWords = wordCount(complete.text);
            if (complete.error != 0 || completeWords == 0)
                throw new AssertionError("length sweep reference error=" + complete.error);
            for (int base : baseSamples) {
                for (int step = 0; step < 8; step++) {
                    int samples = base + step * 160;
                    long started = android.os.SystemClock.elapsedRealtime();
                    Outcome outcome = attempt(fixture, recognizer[0],
                            java.util.Arrays.copyOf(pcm, samples * 2), false);
                    slowestMs = Math.max(slowestMs, android.os.SystemClock.elapsedRealtime() - started);
                    cases++;
                    boolean noText = outcome.error == SpeechRecognizer.ERROR_NO_MATCH
                            || (outcome.error == 0 && (outcome.text == null || outcome.text.trim().isEmpty()));
                    if (!noText && outcome.error != 0)
                        throw new AssertionError("length sweep samples=" + samples + " error=" + outcome.error);
                    if (noText) {
                        empty++;
                        emptyLengths.append(emptyLengths.length() == 0 ? "" : ",").append(samples);
                    } else if (wordCount(outcome.text) * 10L * single.length
                            < completeWords * 6L * samples * 2) {
                        // Under 60% of the proportional share: a cut is mid-word,
                        // and speech density varies, but lost windows are far lower.
                        truncated++;
                        truncatedLengths.append(truncatedLengths.length() == 0 ? "" : ",").append(samples);
                    }
                }
            }
            // Report-only fixed-cost probe: median wall time per length, to separate
            // per-request overhead from audio-proportional decode cost.
            for (int samples : new int[] {16_000, 32_000, 48_000, 80_000, 160_000, 320_000}) {
                long[] ms = new long[3];
                for (int run = 0; run < ms.length; run++) {
                    long started = android.os.SystemClock.elapsedRealtime();
                    attempt(fixture, recognizer[0], java.util.Arrays.copyOf(pcm, samples * 2), false);
                    ms[run] = android.os.SystemClock.elapsedRealtime() - started;
                }
                java.util.Arrays.sort(ms);
                timings.append(timings.length() == 0 ? "" : ",").append(samples).append(':').append(ms[1]);
            }
        } finally {
            fixture.runOnMainSync(() -> recognizer[0].destroy());
            fixture.waitForIdleSync();
        }
        Bundle result = new Bundle();
        result.putInt("length_sweep_cases", cases);
        result.putInt("length_sweep_empty", empty);
        result.putString("length_sweep_empty_samples", emptyLengths.toString());
        result.putInt("length_sweep_truncated", truncated);
        result.putString("length_sweep_truncated_samples", truncatedLengths.toString());
        result.putLong("length_sweep_slowest_ms", slowestMs);
        result.putString("length_sweep_median_ms", timings.toString());
        if (empty != 0 || truncated != 0) throw new LengthSweepFailure(result);
        return result;
    }

    private static int wordCount(String text) {
        if (text == null) return 0;
        String trimmed = text.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }

    static final class LengthSweepFailure extends AssertionError {
        final Bundle evidence;
        LengthSweepFailure(Bundle evidence) {
            super("length sweep produced " + evidence.getInt("length_sweep_empty") + " empty and "
                    + evidence.getInt("length_sweep_truncated") + " truncated transcripts");
            this.evidence = evidence;
        }
    }

    private static byte[] benchmarkPcm(Instrumentation fixture) throws Exception {
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
        return java.util.Arrays.copyOfRange(wav, data, data + length);
    }

    private static void closeReader(ParcelFileDescriptor reader) {
        try { reader.close(); } catch (java.io.IOException ignored) {}
    }

    private static final class Outcome {
        final int error; final String text; final boolean feederFailed;
        Outcome(int error, String text, boolean feederFailed) {
            this.error = error; this.text = text; this.feederFailed = feederFailed;
        }
    }

    private static String recognize(Instrumentation fixture, SpeechRecognizer recognizer,
            byte[] pcm, boolean stop, int expectedError)
            throws Exception {
        Outcome outcome = attempt(fixture, recognizer, pcm, stop);
        if (outcome.error != expectedError) throw new AssertionError("caller error=" + outcome.error
                + " expected=" + expectedError);
        if (expectedError == 0 && outcome.feederFailed) throw new AssertionError("caller feeder failed");
        if (expectedError == 0 && (outcome.text == null || outcome.text.trim().isEmpty()))
            throw new AssertionError("benchmark produced no text");
        return outcome.text;
    }

    private static Outcome attempt(Instrumentation fixture, SpeechRecognizer recognizer,
            byte[] pcm, boolean stop) throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean feederFailed = new java.util.concurrent.atomic.AtomicBoolean();
        String[] text = new String[1];
        int[] error = new int[1];
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        OutputStream writer = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
        try {
            fixture.runOnMainSync(() -> {
                recognizer.setRecognitionListener(new RecognitionListener() {
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
                recognizer.startListening(intent);
            });
            // A separate producer bounds a stalled service independently of the fixture thread.
            Thread feeder = new Thread(() -> {
                try {
                    if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("provider not ready");
                    if (terminal.getCount() == 0) return;
                    writer.write(pcm); writer.flush();
                    if (stop) fixture.runOnMainSync(() -> recognizer.stopListening());
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
            return new Outcome(error[0], text[0], feederFailed.get());
        } finally {
            try { writer.close(); } catch (Exception ignored) {}
            try { pipe[0].close(); } catch (Exception ignored) {}
            fixture.waitForIdleSync();
        }
    }
}
