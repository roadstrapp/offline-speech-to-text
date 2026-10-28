package dev.notune.transcribe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.media.AudioFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * File-transcription contracts against the real service and activity: request admission,
 * error mapping, the no-model answer, bench transcription with an output document, a long
 * (default 25-minute) file, prompt cancellation and the activity result. Only lengths,
 * counts, codes and timings are reported; transcript text stays in memory.
 *
 * <p>Run with {@code -e file_api true}; add {@code -e file_api_minutes N} to change the
 * long-file duration (0 skips it). Without an installed model only the stages that need no
 * model run, and the no-model answer is checked instead.
 */
final class FileApiDeviceTest {
    private static final int TIMEOUT_CODE = -100;

    private final Instrumentation test;
    private final File directory;
    private final Bundle report = new Bundle();
    private SpeechRecognizer recognizer;

    private FileApiDeviceTest(Instrumentation test) {
        this.test = test;
        directory = new File(test.getTargetContext().getCacheDir(), "diagnostics");
    }

    static void run(Instrumentation test, int longMinutes) {
        FileApiDeviceTest fixture = new FileApiDeviceTest(test);
        int code = Activity.RESULT_OK;
        try {
            fixture.runAll(longMinutes);
            fixture.report.putString("status", "PASS");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            fixture.report.putString("status", "FAIL");
            fixture.report.putString("failure", error.toString());
        } finally {
            fixture.close();
        }
        test.finish(code, fixture.report);
    }

    /**
     * Measures the existing caller-audio path ({@code EXTRA_AUDIO_SOURCE}) with a long saved
     * recording: {@code minutes} of the public clip streamed through a pipe, unpaced or at
     * {@code pace} times real time, then EOF. Reports timings and the surviving repetitions.
     */
    static void runCallerAudio(Instrumentation test, int minutes, int pace) {
        FileApiDeviceTest fixture = new FileApiDeviceTest(test);
        int code = Activity.RESULT_OK;
        try {
            fixture.callerAudio(minutes, pace);
            fixture.report.putString("status", "PASS");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            fixture.report.putString("status", "FAIL");
            fixture.report.putString("failure", error.toString());
        } finally {
            fixture.close();
        }
        test.finish(code, fixture.report);
    }

    private void callerAudio(int minutes, int pace) throws Exception {
        check(directory.isDirectory() || directory.mkdirs(), "fixture directory");
        byte[] clip = pcm(copyAsset("file-api-bench.wav"));
        int times = repetitions(clip.length / 2, minutes);
        test.runOnMainSync(() -> recognizer = SpeechRecognizer.createSpeechRecognizer(
                test.getTargetContext(), new ComponentName(test.getTargetContext(), VoiceRecognitionService.class)));
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        Outcome outcome = new Outcome();
        CountDownLatch terminal = new CountDownLatch(1);
        long[] wroteAt = {0};
        long[] written = {0};
        long started = SystemClock.elapsedRealtime();
        test.runOnMainSync(() -> {
            recognizer.setRecognitionListener(listener(outcome, terminal, null));
            recognizer.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT));
        });
        // The recognizer parcels the read end asynchronously; close ours only once the
        // provider holds its own copy (ready) or the request ended.
        long readyDeadline = SystemClock.elapsedRealtime() + 30_000;
        while (!outcome.ready && terminal.getCount() > 0 && SystemClock.elapsedRealtime() < readyDeadline) {
            SystemClock.sleep(10);
        }
        pipe[0].close();
        Thread feeder = new Thread(() -> {
            try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                long t0 = SystemClock.elapsedRealtime();
                for (int i = 0; i < times && terminal.getCount() > 0; i++) {
                    out.write(clip);
                    written[0] += clip.length;
                    if (pace > 0) {
                        long due = t0 + written[0] * 1000L / 32000 / pace;
                        long wait = due - SystemClock.elapsedRealtime();
                        if (wait > 0) SystemClock.sleep(wait);
                    }
                }
            } catch (Exception closedByProvider) {
                // EPIPE: the provider stopped reading.
            }
            wroteAt[0] = SystemClock.elapsedRealtime();
        }, "caller-audio-long");
        feeder.start();
        check(terminal.await(60, TimeUnit.MINUTES), "caller audio timed out");
        feeder.join(10_000);
        report.putLong("caller_minutes", minutes);
        report.putLong("caller_pace", pace);
        report.putLong("caller_error", outcome.error);
        report.putLong("caller_bytes_written", written[0]);
        report.putLong("caller_bytes_total", (long) clip.length * times);
        report.putLong("caller_write_ms", wroteAt[0] - started);
        report.putLong("caller_result_ms", SystemClock.elapsedRealtime() - started);
        report.putLong("caller_country_count", count(words(outcome.text), "country"));
        report.putLong("caller_expected_count", 2L * times);
    }

    private void runAll(int longMinutes) throws Exception {
        check(directory.isDirectory() || directory.mkdirs(), "fixture directory");
        File bench = copyAsset("file-api-bench.wav");
        File text = write("file-api-not-audio.wav", "not audio".getBytes(StandardCharsets.UTF_8));
        File silence = writeWav("file-api-silence.wav", new byte[5 * 16000 * 2], 1);
        File output = write("file-api-output.txt", "stale content that must disappear".getBytes(StandardCharsets.UTF_8));
        test.runOnMainSync(() -> recognizer = SpeechRecognizer.createSpeechRecognizer(
                test.getTargetContext(), new ComponentName(test.getTargetContext(), VoiceRecognitionService.class)));

        // Malformed requests are refused before any model or provider work.
        expectError(request(Uri.fromFile(bench), null, false), SpeechRecognizer.ERROR_CLIENT, "file Uri");
        expectError(request(uri(bench), null, true), SpeechRecognizer.ERROR_CLIENT, "two audio sources");
        Intent badOutput = request(uri(bench), null, false)
                .putExtra(FileTranscriptionContract.EXTRA_OUTPUT_URI, "content://not-a-uri");
        expectError(badOutput, SpeechRecognizer.ERROR_CLIENT, "string output extra");

        boolean model = SpeechModelInventory.hasUsableModel(test.getTargetContext().getFilesDir(),
                BuildConfig.HAS_BUNDLED_MODEL);
        report.putBoolean("model_installed", model);
        if (!model) {
            expectError(request(uri(bench), null, false), 13, "no model");
            report.putString("checks", "admission, no-model ERROR_LANGUAGE_UNAVAILABLE");
            return;
        }

        expectError(request(uri(new File(directory, "file-api-missing.wav")), null, false),
                SpeechRecognizer.ERROR_CLIENT, "unreadable Uri");
        expectError(request(uri(text), null, false), SpeechRecognizer.ERROR_AUDIO, "undecodable");
        expectError(request(uri(silence), null, false), SpeechRecognizer.ERROR_NO_MATCH, "silence");

        long started = SystemClock.elapsedRealtime();
        Outcome benchResult = recognize(request(uri(bench), uri(output), false), 120);
        check(benchResult.error == 0, "bench error " + benchResult.error);
        check(words(benchResult.text).endsWith("for your country"), "bench lost its tail");
        String written = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
        check(written.equals(benchResult.text), "output document differs from onResults");
        check(benchResult.ready && benchResult.endOfSpeech, "ready/end callbacks missing");
        report.putLong("bench_ms", SystemClock.elapsedRealtime() - started);

        // Cancel stops a long job promptly and leaves the service ready for the next one.
        File longFile = longMinutes > 0 ? repeatedWav("file-api-long.wav", bench, longMinutes) : null;
        if (longFile != null) {
            CountDownLatch any = new CountDownLatch(1);
            test.runOnMainSync(() -> {
                recognizer.setRecognitionListener(listener(new Outcome(), any, null));
                recognizer.startListening(request(uri(longFile), null, false));
            });
            SystemClock.sleep(4000);
            test.runOnMainSync(() -> recognizer.cancel());
            check(!any.await(5, TimeUnit.SECONDS), "callback after cancel");
            long next = SystemClock.elapsedRealtime();
            Outcome after = recognize(request(uri(bench), null, false), 120);
            check(after.error == 0 && words(after.text).endsWith("for your country"),
                    "request after cancel failed: " + after.error);
            report.putLong("after_cancel_bench_ms", SystemClock.elapsedRealtime() - next);

            long longStart = SystemClock.elapsedRealtime();
            Outcome longResult = recognize(request(uri(longFile), null, false), 60 * 60);
            check(longResult.error == 0, "long file error " + longResult.error);
            int repeats = count(words(longResult.text), "country");
            // The public clip says "country" twice; the file holds whole repetitions.
            int expected = 2 * repetitions(pcmSamples(bench), longMinutes);
            report.putLong("long_file_minutes", longMinutes);
            report.putLong("long_file_ms", SystemClock.elapsedRealtime() - longStart);
            report.putLong("long_file_chars", longResult.text.length());
            report.putLong("long_file_country_count", repeats);
            report.putLong("long_file_expected_repeats", expected);
            // Every repetition must survive windowing without boundary duplication; allow
            // a few model slips either way.
            check(repeats >= expected * 9 / 10 && repeats <= expected + expected / 20 + 1,
                    "long file windowing: " + repeats + "/" + expected);
        }

        // The activity-result contract, hosted by this app's own ComponentActivity.
        Activity host = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            Files.write(output.toPath(), "stale".getBytes(StandardCharsets.UTF_8));
            AtomicReference<ActivityResult> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Intent intent = new Intent(FileTranscriptionContract.ACTION_TRANSCRIBE_AUDIO)
                    .setPackage(test.getTargetContext().getPackageName())
                    .setDataAndType(uri(bench), "audio/wav")
                    .putExtra(FileTranscriptionContract.EXTRA_OUTPUT_URI, uri(output))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            @SuppressWarnings("unchecked")
            ActivityResultLauncher<Intent>[] launcher = new ActivityResultLauncher[1];
            test.runOnMainSync(() -> {
                launcher[0] = ((androidx.activity.ComponentActivity) host).getActivityResultRegistry()
                        .register("file-api-test", new ActivityResultContracts.StartActivityForResult(),
                                value -> { result.set(value); done.countDown(); });
                launcher[0].launch(intent);
            });
            check(done.await(120, TimeUnit.SECONDS), "activity result timed out");
            test.runOnMainSync(() -> launcher[0].unregister());
            check(result.get().getResultCode() == Activity.RESULT_OK,
                    "activity result code " + result.get().getResultCode());
            ArrayList<String> results = result.get().getData() == null ? null
                    : result.get().getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            check(results != null && results.size() == 1, "EXTRA_RESULTS missing");
            check(words(results.get(0)).endsWith("for your country"), "activity transcript lost tail");
            check(new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8).equals(results.get(0)),
                    "activity output document differs");
        } finally {
            test.runOnMainSync(host::finish);
            test.waitForIdleSync();
        }
        report.putString("checks", "admission, unreadable/undecodable/silence errors, bench+output, "
                + (longFile != null ? "cancel+reuse, long file, " : "") + "activity result+output");
    }

    private void close() {
        if (recognizer != null) {
            test.runOnMainSync(() -> { recognizer.cancel(); recognizer.destroy(); });
            test.waitForIdleSync();
        }
        File[] files = directory.listFiles((dir, name) -> name.startsWith("file-api-"));
        if (files != null) for (File file : files) file.delete();
    }

    // --- Requests ---------------------------------------------------------------------------

    private Intent request(Uri audio, Uri output, boolean withCallerAudio) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(FileTranscriptionContract.EXTRA_AUDIO_URI, audio);
        if (output != null) intent.putExtra(FileTranscriptionContract.EXTRA_OUTPUT_URI, output);
        if (withCallerAudio) {
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                pipe[1].close();
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        }
        return intent;
    }

    private static final class Outcome {
        volatile String text;
        volatile int error = TIMEOUT_CODE;
        volatile boolean ready, endOfSpeech;
    }

    private RecognitionListener listener(Outcome outcome, CountDownLatch terminal, CountDownLatch any) {
        return new RecognitionListener() {
            public void onReadyForSpeech(Bundle b) { outcome.ready = true; }
            public void onBeginningOfSpeech() {}
            public void onRmsChanged(float f) {}
            public void onBufferReceived(byte[] b) {}
            public void onEndOfSpeech() { outcome.endOfSpeech = true; }
            public void onPartialResults(Bundle b) {}
            public void onEvent(int i, Bundle b) {}
            public void onError(int code) { outcome.error = code; terminal.countDown(); }
            public void onResults(Bundle result) {
                ArrayList<String> hypotheses = result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                outcome.text = hypotheses == null || hypotheses.isEmpty() ? "" : hypotheses.get(0);
                outcome.error = 0;
                terminal.countDown();
            }
        };
    }

    private Outcome recognize(Intent intent, int timeoutSeconds) throws InterruptedException {
        Outcome outcome = new Outcome();
        CountDownLatch terminal = new CountDownLatch(1);
        test.runOnMainSync(() -> {
            recognizer.setRecognitionListener(listener(outcome, terminal, null));
            recognizer.startListening(intent);
        });
        if (!terminal.await(timeoutSeconds, TimeUnit.SECONDS)) {
            test.runOnMainSync(() -> recognizer.cancel());
        }
        test.waitForIdleSync();
        return outcome;
    }

    private void expectError(Intent intent, int expected, String label) throws InterruptedException {
        Outcome outcome = recognize(intent, 60);
        check(outcome.error == expected, label + ": error " + outcome.error + " expected " + expected);
    }

    // --- Fixtures ---------------------------------------------------------------------------

    private Uri uri(File file) {
        return new Uri.Builder().scheme("content")
                .authority(test.getTargetContext().getPackageName() + ".fileprovider")
                .appendPath("diagnostic_exports").appendPath(file.getName()).build();
    }

    private File copyAsset(String name) throws Exception {
        File file = new File(directory, name);
        try (InputStream input = test.getTargetContext().getAssets().open("bench.wav")) {
            Files.copy(input, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    private File write(String name, byte[] bytes) throws Exception {
        File file = new File(directory, name);
        Files.write(file.toPath(), bytes);
        return file;
    }

    /** PCM16 mono 16 kHz WAV from {@code pcm} repeated {@code times}, written block-wise. */
    private File writeWav(String name, byte[] pcm, int times) throws Exception {
        File file = new File(directory, name);
        long data = (long) pcm.length * times;
        try (OutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(file), 1 << 16)) {
            out.write(new byte[] {'R', 'I', 'F', 'F'});
            le32(out, 36 + data);
            out.write(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
            le32(out, 16); le16(out, 1); le16(out, 1); le32(out, 16000); le32(out, 32000);
            le16(out, 2); le16(out, 16);
            out.write(new byte[] {'d', 'a', 't', 'a'});
            le32(out, data);
            for (int i = 0; i < times; i++) out.write(pcm);
        }
        return file;
    }

    private File repeatedWav(String name, File bench, int minutes) throws Exception {
        byte[] pcm = pcm(bench);
        return writeWav(name, pcm, repetitions(pcm.length / 2, minutes));
    }

    private static int repetitions(int clipSamples, int minutes) {
        return (int) Math.ceil(minutes * 60.0 * 16000 / clipSamples);
    }

    private int pcmSamples(File bench) throws Exception {
        return pcm(bench).length / 2;
    }

    /** The packaged benchmark's data chunk (PCM16 mono 16 kHz, checked by CallerAudioProviderDeviceTest). */
    private static byte[] pcm(File wavFile) throws Exception {
        byte[] wav = Files.readAllBytes(wavFile.toPath());
        int offset = 12;
        while (offset + 8 <= wav.length) {
            int n = (wav[offset + 4] & 255) | ((wav[offset + 5] & 255) << 8)
                    | ((wav[offset + 6] & 255) << 16) | ((wav[offset + 7] & 255) << 24);
            if (wav[offset] == 'd' && wav[offset + 1] == 'a' && wav[offset + 2] == 't' && wav[offset + 3] == 'a') {
                return java.util.Arrays.copyOfRange(wav, offset + 8, offset + 8 + n);
            }
            offset += 8 + n + (n & 1);
        }
        throw new AssertionError("benchmark data chunk missing");
    }

    private static void le16(OutputStream out, int v) throws java.io.IOException {
        out.write(v & 255); out.write((v >> 8) & 255);
    }

    private static void le32(OutputStream out, long v) throws java.io.IOException {
        for (int i = 0; i < 4; i++) out.write((int) (v >> (8 * i)) & 255);
    }

    private static String words(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ").trim();
    }

    private static int count(String haystack, String word) {
        int n = 0;
        for (String w : haystack.split(" ")) if (w.equals(word)) n++;
        return n;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
