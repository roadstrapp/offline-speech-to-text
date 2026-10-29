package dev.notune.transcribe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;

/** Explicit isolated-device qualification; only pinned public models and audio. */
final class ModelQualificationDeviceTest {
    private final Instrumentation test;
    private Activity models;
    private Activity fileActivity;
    private String selectedName;
    private int expectedThreads;
    private final java.util.Set<Activity> fileInstances = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final JSONObject report = new JSONObject();
    private final File directory;
    private static final String REFERENCE = "parakeet-tdt-0.6b-v3-Q8_0.gguf";
    private static final String EXPECTED = "and so my fellow americans ask not what your country can do for you ask what you can do for your country";

    private ModelQualificationDeviceTest(Instrumentation test) {
        this.test = test;
        this.directory = test.getTargetContext().getFilesDir();
    }

    static void runPrepared(Instrumentation test) { runPrepared(test, false); }

    static void runSetup(Instrumentation test) { runPrepared(test, true); }

    private static void runPrepared(Instrumentation test, boolean setupOnly) {
        ModelQualificationDeviceTest fixture = new ModelQualificationDeviceTest(test);
        Bundle result = new Bundle();
        Activity main = null;
        int code = Activity.RESULT_OK;
        try {
            fixture.check(test.getTargetContext().getPackageName().equals("app.offlinespeechtotext.validation"), "isolated package required");
            fixture.check(!BuildConfig.HAS_BUNDLED_MODEL, "unexpected bundled weights");
            Files.write(new File(fixture.directory, "model_language").toPath(), "en".getBytes(StandardCharsets.UTF_8));
            Files.deleteIfExists(new File(fixture.directory, "model_languages").toPath());
            Files.write(new File(fixture.directory, "model_threads").toPath(), "2".getBytes(StandardCharsets.UTF_8));
            fixture.expectedThreads = 2;
            fixture.models = test.startActivitySync(new Intent(test.getTargetContext(), ModelsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            fixture.download(ModelCatalog.findByFileName(REFERENCE));
            fixture.select(REFERENCE);
            if (!setupOnly) {
                fixture.report.put("transcription", fixture.transcribe());
                fixture.closeFile();
                main = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                long[] evidence = PreparedWindowsDeviceTest.run(test, main);
                fixture.report.put("prepared_windows", evidence[1]);
                fixture.report.put("deferred_ms", evidence[2]);
                fixture.report.put("early_ms", evidence[3]);
                fixture.report.put("prepared_stop_ms", evidence[4]);
            }
            fixture.report.put("status", "PASS");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            try { fixture.report.put("status", "FAIL"); fixture.report.put("failure", error.toString()); }
            catch (Exception ignored) { }
        } finally {
            fixture.closeFile();
            if (main != null) { Activity closing = main; test.runOnMainSync(closing::finish); }
            if (fixture.models != null) test.runOnMainSync(fixture.models::finish);
        }
        result.putString(setupOnly ? "model_setup" : "prepared_qualification", fixture.report.toString());
        test.finish(code, result);
    }

    static void run(Instrumentation test, String name, boolean bilingual) {
        ModelQualificationDeviceTest fixture = new ModelQualificationDeviceTest(test);
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            fixture.check(test.getTargetContext().getPackageName().equals("app.offlinespeechtotext.validation"), "isolated package required");
            fixture.check(!BuildConfig.HAS_BUNDLED_MODEL && !SpeechModelInventory.hasRetainedBuiltInModel(fixture.directory), "fallback model invalidates qualification");
            ModelDescriptor model = ModelCatalog.findByFileName(name);
            fixture.check(model != null, "unknown pinned descriptor");
            fixture.report.put("file", name);
            fixture.report.put("sha256", model.sha256);
            Files.write(new File(fixture.directory, "model_language").toPath(), "auto".getBytes(StandardCharsets.UTF_8));
            Files.deleteIfExists(new File(fixture.directory, "model_translate").toPath());
            Files.deleteIfExists(new File(fixture.directory, "model_threads").toPath());
            fixture.report.put("settings", "Initial automatic language and default threads; each transcription records its effective loaded configuration");
            fixture.models = test.startActivitySync(new Intent(test.getTargetContext(), ModelsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            fixture.download(model);
            fixture.select(name);
            fixture.report.put("transcription", fixture.transcribe());
            if (bilingual) fixture.bilingual(name);
            String referenceName = REFERENCE.equals(name) ? DefaultModelContract.FILE_NAME : REFERENCE;
            ModelDescriptor reference = ModelCatalog.findByFileName(referenceName);
            fixture.download(reference);
            fixture.select(referenceName);
            fixture.report.put("switch_back_reference", fixture.transcribe());
            fixture.select(name);
            fixture.report.put("switch_again_target", fixture.transcribe());
            fixture.report.put("status", "PASS");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            try { fixture.report.put("status", "FAIL"); fixture.report.put("failure", error.toString()); }
            catch (Exception ignored) { }
        } finally {
            fixture.closeFile();
            if (fixture.models != null) test.runOnMainSync(fixture.models::finish);
        }
        result.putString("model_qualification", fixture.report.toString());
        test.finish(code, result);
    }

    private void bilingual(String name) throws Exception {
        check("canary-1b-v2-Q4_K_M.gguf".equals(name), "bilingual fixture requires pinned Canary v2");
        ModelDescriptor detector = ModelCatalog.languageId();
        stage("download language detector");
        java.util.concurrent.CountDownLatch terminal = new java.util.concurrent.CountDownLatch(1);
        String[] failure = new String[1];
        ModelDownloadTask.Handle handle = ModelDownloadTask.start(directory, detector, new ModelDownloadTask.Listener() {
            public void onStarted(ModelDownloadTask.Handle handle) { }
            public void onProgress(long downloaded, long total) { }
            public void onComplete(String installed) { terminal.countDown(); }
            public void onFailure(String reason) { failure[0] = reason; terminal.countDown(); }
        });
        if (!terminal.await(180, java.util.concurrent.TimeUnit.SECONDS)) {
            handle.cancel();
            check(terminal.await(40, java.util.concurrent.TimeUnit.SECONDS), "detector cancellation did not finish");
            throw new AssertionError("detector download timed out");
        }
        check(failure[0] == null, "detector download failed: " + failure[0]);
        check(SpokenLanguages.detectorInstalled(directory), "auxiliary download missing provenance");
        check(new String(Files.readAllBytes(new File(directory, "active_model").toPath()), StandardCharsets.UTF_8).trim().equals(name), "auxiliary model changed selection");
        File auxiliary = new File(new File(directory, ModelInstaller.AUXILIARY_DIRECTORY), detector.fileName);
        check(hash(auxiliary).equals(detector.sha256), "detector bytes not pinned");
        Files.write(new File(directory, "model_language").toPath(), "en".getBytes(StandardCharsets.UTF_8));
        SpokenLanguages.write(directory, java.util.Arrays.asList("en", "sk"));
        report.put("language_list", new org.json.JSONArray(SpokenLanguages.read(directory)));
        for (int threads : new int[] {2, 4}) {
            Files.write(new File(directory, "model_threads").toPath(), Integer.toString(threads).getBytes(StandardCharsets.UTF_8));
            expectedThreads = threads;
            select(name);
            JSONObject english = transcribe();
            check(english.getDouble("word_error_rate") <= 0.15, "Canary English fixture accuracy");
            JSONObject slovak = transcribe("fleurs-sk.wav", "87bb277cd46efdae0747510be7c6c63cd2a0bc37fcd31645fc09113887d45a9b",
                    "madagaskar je zďaleka tým najväčším a je sám o sebe kontinentom keď ide o divokú prírodu");
            check(slovak.getDouble("word_error_rate") <= 0.35, "Canary Slovak fixture accuracy");
            report.put("threads_" + threads + "_english", english);
            report.put("threads_" + threads + "_slovak", slovak);
        }
        stage("missing detector fails closed");
        File held = new File(auxiliary.getPath() + ".fixture-held");
        check(!held.exists(), "stale detector fixture exists");
        Files.move(auxiliary.toPath(), held.toPath());
        try {
            java.lang.reflect.Method select = ModelsActivity.class.getDeclaredMethod("selectModel", String.class);
            select.setAccessible(true);
            test.runOnMainSync(() -> { try { select.invoke(models, name); } catch (Exception e) { throw new AssertionError(e); } });
            long deadline = SystemClock.elapsedRealtime() + 300000;
            boolean rejected = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                String[] status = new String[1];
                test.runOnMainSync(() -> status[0] = ((TextView)models.findViewById(R.id.txt_model_status)).getText().toString());
                if (status[0].startsWith("Error")) {
                    check(status[0].contains("language detector"), "unexpected failure: " + status[0]);
                    check(loadedPath() == null, "failed load kept a fallback engine");
                    rejected = true; break;
                }
                SystemClock.sleep(100);
            }
            check(rejected, "missing detector silently accepted");
            report.put("missing_detector_rejected", true);
        } finally { Files.move(held.toPath(), auxiliary.toPath()); }
        select(name);
        report.put("detector_recovery", transcribe());
        ModelDescriptor englishOnly = ModelCatalog.findByFileName("whisper-tiny.en-Q4_K_M.gguf");
        download(englishOnly); select(englishOnly.fileName);
        String[] status = new String[1];
        test.runOnMainSync(() -> status[0] = ((TextView)models.findViewById(R.id.txt_model_status)).getText().toString());
        check("Ready (this model supports English only)".equals(status[0]), "English-only status missing");
        JSONObject english = transcribe();
        check(english.getDouble("word_error_rate") <= 0.15, "English-only switch fixture accuracy");
        check(SpokenLanguages.read(directory).equals(java.util.Arrays.asList("en", "sk")), "model switch erased saved list");
        report.put("english_only_switch", english);
        select(name);
        report.put("canary_switch_back", transcribe());
    }

    private void stage(String name) throws Exception {
        report.put("stage", name);
        Bundle status = new Bundle(); status.putString("qualification_progress", report.optString("file") + ": " + name);
        test.sendStatus(0, status);
    }

    private void download(ModelDescriptor model) throws Exception {
        File file = new File(new File(directory, "models"), model.fileName);
        if (VerifiedModelProvenance.isVerified(directory, file, model)) {
            stage("hash " + model.fileName);
            check(hash(file).equals(model.sha256), "reused model bytes do not match pinned digest");
            report.put("download_reused_" + model.id, true);
            return;
        }
        stage("network preflight " + model.fileName);
        java.lang.reflect.Method usable = ModelsActivity.class.getDeclaredMethod("hasUsableNetworkAccess");
        usable.setAccessible(true);
        boolean[] ready = new boolean[1];
        long networkDeadline = SystemClock.elapsedRealtime() + 30000;
        do {
            test.runOnMainSync(() -> {
                try { ready[0] = (boolean)usable.invoke(models); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            if (!ready[0]) SystemClock.sleep(250);
        } while (!ready[0] && SystemClock.elapsedRealtime() < networkDeadline);
        android.net.ConnectivityManager manager = (android.net.ConnectivityManager)test.getTargetContext()
                .getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        android.net.Network active = manager == null ? null : manager.getActiveNetwork();
        android.net.NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
        JSONObject network = new JSONObject();
        network.put("internet_permission", test.getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET) == android.content.pm.PackageManager.PERMISSION_GRANTED);
        network.put("active_network", active != null);
        network.put("internet_capability", capabilities != null
                && capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET));
        report.put("network_preflight_" + model.id, network);
        check(ready[0], "network unavailable before download; not a model failure: " + network);
        stage("download " + model.fileName); long started = SystemClock.elapsedRealtime();
        java.lang.reflect.Method begin = ModelsActivity.class.getDeclaredMethod("beginDownload", ModelDescriptor.class, String.class);
        begin.setAccessible(true);
        java.lang.reflect.Field lifecycleField = ModelsActivity.class.getDeclaredField("DOWNLOAD_LIFECYCLE");
        lifecycleField.setAccessible(true);
        ModelDownloadLifecycle lifecycle = (ModelDownloadLifecycle) lifecycleField.get(null);
        test.runOnMainSync(() -> {
            try { begin.invoke(models, model, "catalog"); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        check(lifecycle.snapshot().state == ModelDownloadLifecycle.State.RUNNING
                && model.id.equals(lifecycle.snapshot().modelId), "download did not start for requested model; check network/busy UI");
        long lastProgress = 0;
        while (SystemClock.elapsedRealtime() - started < 1200000) {
            ModelDownloadLifecycle.Snapshot snapshot = lifecycle.snapshot();
            if (snapshot.state == ModelDownloadLifecycle.State.FAILED) throw new AssertionError("download: " + snapshot.failure);
            if (!lifecycle.isRunning() && VerifiedModelProvenance.isVerified(directory, file, model)) {
                stage("hash " + model.fileName);
                check(hash(file).equals(model.sha256), "downloaded bytes do not match pinned digest");
                report.put("download_ms_" + model.id, SystemClock.elapsedRealtime() - started);
                report.put("download_bytes_" + model.id, file.length());
                stage("select downloaded " + model.fileName);
                awaitLoaded(model.fileName);
                return;
            }
            if (SystemClock.elapsedRealtime() - lastProgress > 15000) {
                stage("download " + model.fileName + " " + snapshot.downloaded + "/" + snapshot.total);
                lastProgress = SystemClock.elapsedRealtime();
            }
            SystemClock.sleep(250);
        }
        lifecycle.cancel();
        long cancelDeadline = SystemClock.elapsedRealtime() + 35000;
        while (lifecycle.isRunning() && SystemClock.elapsedRealtime() < cancelDeadline) SystemClock.sleep(100);
        check(!lifecycle.isRunning(), "download cancellation still pending; host must stop isolated process");
        throw new AssertionError("download timeout (not proof of model incompatibility)");
    }

    private void select(String name) throws Exception {
        stage("select " + name);
        String previousConfiguration = loadedConfiguration();
        java.lang.reflect.Method select = ModelsActivity.class.getDeclaredMethod("selectModel", String.class);
        select.setAccessible(true);
        long deadline = SystemClock.elapsedRealtime() + 300000;
        test.runOnMainSync(() -> {
            try { select.invoke(models, name); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        awaitLoaded(name, deadline, previousConfiguration);
    }

    private void awaitLoaded(String name) throws Exception {
        awaitLoaded(name, SystemClock.elapsedRealtime() + 300000, null);
    }

    private void awaitLoaded(String name, long deadline, String previousConfiguration) throws Exception {
        while (SystemClock.elapsedRealtime() < deadline) {
            String[] status = new String[1];
            test.runOnMainSync(() -> status[0] = ((TextView) models.findViewById(R.id.txt_model_status)).getText().toString());
            if (status[0].startsWith("Error")) throw new AssertionError("load " + name + ": " + status[0]);
            String actualPath = loadedPath();
            String expectedPath = new File(new File(directory, "models"), name).getAbsolutePath();
            String configuration = loadedConfiguration();
            ModelDescriptor descriptor = ModelCatalog.findByFileName(name);
            String detector = SpokenLanguages.needsDetector(SpokenLanguages.read(directory))
                    && descriptor.languageChoice == ModelDescriptor.Capability.SUPPORTED
                    && descriptor.languages.size() > 1 ? String.join(",", SpokenLanguages.read(directory)) : "";
            boolean matches = configuration != null && !configuration.equals(previousConfiguration)
                    && (expectedThreads == 0 || configuration.contains(";threads=" + expectedThreads + ";"))
                    && configuration.endsWith(";detector=" + detector);
            if (status[0].startsWith("Ready") && expectedPath.equals(actualPath) && matches) {
                check(new String(Files.readAllBytes(new File(directory, "active_model").toPath()), StandardCharsets.UTF_8).trim().equals(name), "selection marker mismatch");
                selectedName = name;
                report.put("loaded_path_" + name, actualPath);
                report.put("loaded_configuration_" + name, configuration);
                return;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("load timeout (not proof of unsupported model): " + name);
    }

    private JSONObject transcribe() throws Exception {
        return transcribe("bench.wav", "59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e", EXPECTED);
    }

    private JSONObject transcribe(String asset, String digest, String expected) throws Exception {
        stage("transcribe public " + asset);
        File cache = new File(test.getTargetContext().getCacheDir(), "diagnostics"); cache.mkdirs();
        File audio = new File(cache, "public-model-qualification.wav");
        try (java.io.InputStream input = ("bench.wav".equals(asset) ? test.getTargetContext() : test.getContext()).getAssets().open(asset)) {
            Files.copy(input, audio.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        check(hash(audio).equals(digest), "public audio digest mismatch");
        String modelPath = new File(new File(directory, "models"), selectedName).getAbsolutePath();
        check(modelPath.equals(loadedPath()), "wrong engine before file decode");
        String configuration = loadedConfiguration();
        check(configuration != null, "loaded configuration unavailable before decode");
        Uri uri = new Uri.Builder().scheme("content").authority("app.offlinespeechtotext.validation.test.public_audio")
                .appendPath(asset).build();
        long start = SystemClock.elapsedRealtime();
        fileActivity = test.startActivitySync(new Intent(Intent.ACTION_VIEW, uri, test.getTargetContext(), TranscribeFileActivity.class)
                .setDataAndType(uri,"audio/wav").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
        check(fileInstances.add(fileActivity), "reused file activity invalidates fresh completion check");
        java.lang.reflect.Field finishedField = TranscribeFileActivity.class.getDeclaredField("finished");
        finishedField.setAccessible(true);
        try {
            while (SystemClock.elapsedRealtime() - start < 300000) {
                String[] text = new String[2]; boolean[] complete = new boolean[1];
                test.runOnMainSync(() -> {
                    text[0] = ((TextView) fileActivity.findViewById(R.id.txt_result)).getText().toString();
                    text[1] = ((TextView) fileActivity.findViewById(R.id.txt_status)).getText().toString();
                    try { complete[0] = finishedField.getBoolean(fileActivity); }
                    catch (Exception error) { throw new AssertionError(error); }
                    complete[0] = complete[0] && fileActivity.findViewById(R.id.btn_copy).getVisibility() == View.VISIBLE
                            && fileActivity.findViewById(R.id.progress_area).getVisibility() == View.GONE;
                });
                if (text[1].startsWith("Error")) throw new AssertionError("file inference: " + text[1]);
                if (complete[0]) {
                    check(!text[0].trim().isEmpty(), "empty transcript");
                    check(modelPath.equals(loadedPath()), "engine changed during file decode");
                    check(configuration.equals(loadedConfiguration()), "loaded engine configuration changed during decode");
                    JSONObject value = new JSONObject(); value.put("text", text[0]); value.put("wall_ms",SystemClock.elapsedRealtime()-start);
                    value.put("word_error_rate", wordErrorRate(expected, text[0]));
                    value.put("model_file", selectedName);
                    value.put("loaded_configuration", configuration);
                    value.put("requested_thread_setting", new File(directory, "model_threads").exists()
                            ? new String(Files.readAllBytes(new File(directory, "model_threads").toPath()), StandardCharsets.UTF_8) : "auto");
                    value.put("languages", new org.json.JSONArray(SpokenLanguages.read(directory)));
                    value.put("fresh_final_callback", true);
                    value.put("audio_sha256", digest);
                    return value;
                }
                SystemClock.sleep(100);
            }
            throw new AssertionError("inference timeout (not proof of unsupported model)");
        } finally { closeFile(); Files.deleteIfExists(audio.toPath()); }
    }

    private String loadedPath() throws Exception {
        java.lang.reflect.Method method = ModelsActivity.class.getDeclaredMethod("loadedModelPathNative");
        method.setAccessible(true);
        return (String) method.invoke(models);
    }

    private String loadedConfiguration() throws Exception {
        java.lang.reflect.Method method = ModelsActivity.class.getDeclaredMethod("loadedModelConfigurationNative");
        method.setAccessible(true);
        return (String)method.invoke(models);
    }

    private static String hash(File file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[1024 * 1024]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder();
        for (byte octet : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", octet & 255));
        return value.toString();
    }

    private static double wordErrorRate(String reference, String text) {
        String[] expected = reference.split(" ");
        String[] actual = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]+", " ").trim().split(" +");
        int[][] distance = new int[expected.length+1][actual.length+1];
        for(int i=0;i<=expected.length;i++) distance[i][0]=i;
        for(int j=0;j<=actual.length;j++) distance[0][j]=j;
        for(int i=1;i<=expected.length;i++) for(int j=1;j<=actual.length;j++)
            distance[i][j]=Math.min(Math.min(distance[i-1][j]+1,distance[i][j-1]+1),distance[i-1][j-1]+(expected[i-1].equals(actual[j-1])?0:1));
        return (double)distance[expected.length][actual.length]/expected.length;
    }

    private void closeFile() { if (fileActivity != null) { Activity active=fileActivity; test.runOnMainSync(active::finish); test.waitForIdleSync(); fileActivity=null; } }
    private void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
