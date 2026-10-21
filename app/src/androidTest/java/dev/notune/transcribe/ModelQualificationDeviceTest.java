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
    private final java.util.Set<Activity> fileInstances = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final JSONObject report = new JSONObject();
    private final File directory;
    private static final String REFERENCE = "parakeet-tdt-0.6b-v3-Q8_0.gguf";
    private static final String EXPECTED = "and so my fellow americans ask not what your country can do for you ask what you can do for your country";

    private ModelQualificationDeviceTest(Instrumentation test) {
        this.test = test;
        this.directory = test.getTargetContext().getFilesDir();
    }

    static void run(Instrumentation test, String name) {
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
            fixture.report.put("settings", "automatic language; translation off; production thread default");
            fixture.models = test.startActivitySync(new Intent(test.getTargetContext(), ModelsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            fixture.download(model);
            fixture.select(name);
            fixture.report.put("transcription", fixture.transcribe());
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
        java.lang.reflect.Method select = ModelsActivity.class.getDeclaredMethod("selectModel", String.class);
        select.setAccessible(true);
        long deadline = SystemClock.elapsedRealtime() + 300000;
        test.runOnMainSync(() -> {
            try { select.invoke(models, name); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        awaitLoaded(name, deadline);
    }

    private void awaitLoaded(String name) throws Exception {
        awaitLoaded(name, SystemClock.elapsedRealtime() + 300000);
    }

    private void awaitLoaded(String name, long deadline) throws Exception {
        while (SystemClock.elapsedRealtime() < deadline) {
            String[] status = new String[1];
            test.runOnMainSync(() -> status[0] = ((TextView) models.findViewById(R.id.txt_model_status)).getText().toString());
            if (status[0].startsWith("Error")) throw new AssertionError("load " + name + ": " + status[0]);
            String actualPath = loadedPath();
            String expectedPath = new File(new File(directory, "models"), name).getAbsolutePath();
            if (status[0].startsWith("Ready") && expectedPath.equals(actualPath)) {
                check(new String(Files.readAllBytes(new File(directory, "active_model").toPath()), StandardCharsets.UTF_8).trim().equals(name), "selection marker mismatch");
                selectedName = name;
                report.put("loaded_path_" + name, actualPath);
                return;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("load timeout (not proof of unsupported model): " + name);
    }

    private JSONObject transcribe() throws Exception {
        stage("transcribe public JFK audio");
        File cache = new File(test.getTargetContext().getCacheDir(), "diagnostics"); cache.mkdirs();
        File audio = new File(cache, "public-model-qualification.wav");
        try (java.io.InputStream input = test.getTargetContext().getAssets().open("bench.wav")) {
            Files.copy(input, audio.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        check(hash(audio).equals("59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e"), "public audio digest mismatch");
        String modelPath = new File(new File(directory, "models"), selectedName).getAbsolutePath();
        check(modelPath.equals(loadedPath()), "wrong engine before file decode");
        Uri uri = new Uri.Builder().scheme("content").authority(test.getTargetContext().getPackageName()+".fileprovider")
                .appendPath("diagnostic_exports").appendPath(audio.getName()).build();
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
                    JSONObject value = new JSONObject(); value.put("text", text[0]); value.put("wall_ms",SystemClock.elapsedRealtime()-start);
                    value.put("word_error_rate", wordErrorRate(text[0]));
                    value.put("model_file", selectedName);
                    value.put("fresh_final_callback", true);
                    value.put("audio_sha256", "59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e");
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

    private static double wordErrorRate(String text) {
        String[] expected = EXPECTED.split(" ");
        String[] actual = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ").trim().split(" +");
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
