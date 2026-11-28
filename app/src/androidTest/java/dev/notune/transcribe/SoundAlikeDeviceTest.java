package dev.notune.transcribe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Sound-alike Preferred-words probes through the real storage path (CustomTerms, the
 * model_language hint) and the real file-transcription path (VoiceRecognitionService with
 * EXTRA_AUDIO_URI). Requires the model installed by {@code qualify_setup}.
 *
 * <p>{@code -e sound_alike_probe true}: every synthetic clip is transcribed with language
 * Automatic and English, each with no terms (the raw ASR text), with the two-term list
 * (Nostr, Tailscale) and with the 20-term list. The shipping file API does not expose the
 * output language or the gate, so the gate is reported as inferred from the language
 * setting and the raw text, never as observed.
 *
 * <p>{@code -e preferred_terms_timing true}: one longer clip, 10 runs each at 0, 20 and 128
 * terms, wall clock per run and medians. Settings are restored at the end of each probe.
 */
final class SoundAlikeDeviceTest {
    private static final String[][] CLIPS = {
        {"tail_scale", "sound-alike-tail-scale.wav", "I set up tail scale on the server today"},
        {"noster", "sound-alike-noster.wav", "I read about the noster protocol this morning"},
        {"mixed_es_en", "sound-alike-mixed-es-en.wav",
            "Yo creo que nuestro proyecto is going to work well for the team"},
        {"control_fish", "sound-alike-control-fish.wav", "The tail scale of the fish was bright"},
    };
    private static final String[] SETTING_FILES = {"custom_terms", "model_language", "model_languages"};
    private static final int TIMING_RUNS = 10;
    private static final int[] TIMING_TERMS = {0, 20, 128};
    private static final int TIMEOUT_SECONDS = 180;

    private final Instrumentation test;
    private final File filesDir;
    private final File directory;
    private final Bundle report = new Bundle();
    private final List<String> terms128 = new ArrayList<>();
    private SpeechRecognizer recognizer;
    private final byte[][] savedSettings = new byte[SETTING_FILES.length][];

    private SoundAlikeDeviceTest(Instrumentation test) {
        this.test = test;
        filesDir = test.getTargetContext().getFilesDir();
        directory = new File(test.getTargetContext().getCacheDir(), "diagnostics");
    }

    static void runProbe(Instrumentation test) { run(test, true); }

    static void runTiming(Instrumentation test) { run(test, false); }

    private static void run(Instrumentation test, boolean probe) {
        SoundAlikeDeviceTest fixture = new SoundAlikeDeviceTest(test);
        int code = Activity.RESULT_OK;
        try {
            fixture.open();
            if (probe) fixture.probe(); else fixture.timing();
            fixture.report.putString("status", "PASS");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            fixture.report.putString("status", "FAIL");
            fixture.report.putString("failure", error.toString());
        } finally {
            try {
                fixture.close();
                fixture.report.putString("settings_restored", "true");
            } catch (Throwable error) {
                code = Activity.RESULT_CANCELED;
                fixture.report.putString("status", "FAIL");
                fixture.report.putString("failure", "restore: " + error);
            }
        }
        test.finish(code, fixture.report);
    }

    // --- Probes -----------------------------------------------------------------------------

    private void probe() throws Exception {
        List<String> pair = Arrays.asList("Nostr", "Tailscale");
        List<String> twenty = terms128.subList(0, 20);
        String[][] modes = {{"auto", "auto"}, {"en", "en"}};
        for (String[] mode : modes) {
            setLanguage(mode[1]);
            for (String[] clip : CLIPS) {
                File wav = copyTestAsset(clip[1]);
                String raw = transcribe(wav, Collections.emptyList());
                String withPair = transcribe(wav, pair);
                String withTwenty = transcribe(wav, twenty);
                String key = "probe_" + mode[0] + "_" + clip[0];
                report.putString(key + "_raw", oneLine(raw));
                report.putString(key + "_terms2", oneLine(withPair));
                report.putString(key + "_terms20", oneLine(withTwenty));
                report.putString(key + "_changed", String.valueOf(!raw.equals(withPair) || !raw.equals(withTwenty)));
                report.putString(key + "_gate", inferredGate(mode[0], raw));
                report.putString(key + "_spoken", clip[2]);
            }
        }
        report.putString("probe_note", "gate is inferred from the language setting and raw text; "
                + "the file API exposes neither the output language nor the gate");
    }

    private void timing() throws Exception {
        setLanguage("auto");
        File wav = copyMainAsset("bench.wav");
        report.putString("timing_clip_bytes", String.valueOf(wav.length()));
        transcribe(wav, Collections.emptyList()); // warm-up, not reported
        long[][] runs = new long[TIMING_TERMS.length][TIMING_RUNS];
        // Interleave term counts so drift affects every count the same way.
        for (int run = 0; run < TIMING_RUNS; run++) {
            for (int i = 0; i < TIMING_TERMS.length; i++) {
                List<String> terms = terms128.subList(0, TIMING_TERMS[i]);
                long start = SystemClock.elapsedRealtime();
                String text = transcribe(wav, terms);
                runs[i][run] = SystemClock.elapsedRealtime() - start;
                if (text.isEmpty()) throw new AssertionError("empty transcript at terms=" + TIMING_TERMS[i]);
            }
        }
        for (int i = 0; i < TIMING_TERMS.length; i++) {
            String prefix = "timing_terms" + TIMING_TERMS[i];
            report.putString(prefix + "_runs_ms", Arrays.toString(runs[i]));
            long[] sorted = runs[i].clone();
            Arrays.sort(sorted);
            report.putString(prefix + "_median_ms", String.valueOf((sorted[TIMING_RUNS / 2 - 1] + sorted[TIMING_RUNS / 2]) / 2.0));
        }
    }

    private static String inferredGate(String mode, String raw) {
        if (mode.equals("en")) return "inferred:english(single+multi)";
        boolean ascii = raw.chars().allMatch(c -> c < 128);
        return "inferred:unknown(multi_common_only=" + ascii + ";single_if_looks_english)";
    }

    // --- Settings (real storage path) -------------------------------------------------------

    private void open() throws Exception {
        for (int i = 0; i < SETTING_FILES.length; i++) {
            File file = new File(filesDir, SETTING_FILES[i]);
            savedSettings[i] = file.isFile() ? Files.readAllBytes(file.toPath()) : null;
        }
        try (InputStream in = test.getContext().getAssets().open("sound-alike-terms-128.txt")) {
            String all = new String(readAll(in), StandardCharsets.UTF_8);
            for (String line : all.split("\n")) if (!line.trim().isEmpty()) terms128.add(line.trim());
        }
        if (terms128.size() != 128) throw new AssertionError("terms asset has " + terms128.size());
        if (!directory.isDirectory() && !directory.mkdirs()) throw new AssertionError("fixture directory");
        test.runOnMainSync(() -> recognizer = SpeechRecognizer.createSpeechRecognizer(
                test.getTargetContext(), new ComponentName(test.getTargetContext(), VoiceRecognitionService.class)));
    }

    private void close() throws Exception {
        try {
            if (recognizer != null) {
                test.runOnMainSync(() -> { recognizer.cancel(); recognizer.destroy(); });
                test.waitForIdleSync();
            }
        } finally {
            File[] files = directory.listFiles((dir, name) -> name.startsWith("sound-alike-"));
            if (files != null) for (File file : files) file.delete();
            for (int i = 0; i < SETTING_FILES.length; i++) {
                File file = new File(filesDir, SETTING_FILES[i]);
                if (savedSettings[i] == null) Files.deleteIfExists(file.toPath());
                else Files.write(file.toPath(), savedSettings[i]);
            }
        }
    }

    private void setLanguage(String hint) throws Exception {
        Files.deleteIfExists(new File(filesDir, SpokenLanguages.LIST_FILE).toPath());
        Files.write(new File(filesDir, "model_language").toPath(), hint.getBytes(StandardCharsets.UTF_8));
    }

    private void setTerms(List<String> terms) throws Exception {
        CustomTerms.write(filesDir, String.join("\n", terms));
    }

    // --- File transcription -----------------------------------------------------------------

    private String transcribe(File wav, List<String> terms) throws Exception {
        setTerms(terms);
        Uri audio = new Uri.Builder().scheme("content")
                .authority(test.getTargetContext().getPackageName() + ".fileprovider")
                .appendPath("diagnostic_exports").appendPath(wav.getName()).build();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(FileTranscriptionContract.EXTRA_AUDIO_URI, audio);
        String[] text = {null};
        int[] error = {-100};
        CountDownLatch terminal = new CountDownLatch(1);
        test.runOnMainSync(() -> {
            recognizer.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle b) {}
                public void onBeginningOfSpeech() {}
                public void onRmsChanged(float f) {}
                public void onBufferReceived(byte[] b) {}
                public void onEndOfSpeech() {}
                public void onPartialResults(Bundle b) {}
                public void onEvent(int i, Bundle b) {}
                public void onError(int code) { error[0] = code; terminal.countDown(); }
                public void onResults(Bundle result) {
                    ArrayList<String> hypotheses = result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    text[0] = hypotheses == null || hypotheses.isEmpty() ? "" : hypotheses.get(0);
                    error[0] = 0;
                    terminal.countDown();
                }
            });
            recognizer.startListening(intent);
        });
        if (!terminal.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            test.runOnMainSync(() -> recognizer.cancel());
        }
        test.waitForIdleSync();
        if (error[0] != 0 || text[0] == null) {
            throw new AssertionError("transcription of " + wav.getName() + " failed: error " + error[0]);
        }
        return text[0];
    }

    private File copyTestAsset(String name) throws Exception {
        return copy(test.getContext().getAssets().open(name), name);
    }

    private File copyMainAsset(String name) throws Exception {
        return copy(test.getTargetContext().getAssets().open(name), "sound-alike-main-" + name);
    }

    private File copy(InputStream input, String name) throws Exception {
        File file = new File(directory, name);
        try (InputStream in = input) {
            Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static String oneLine(String text) {
        return text.replace('\n', ' ').replace('\r', ' ');
    }
}
