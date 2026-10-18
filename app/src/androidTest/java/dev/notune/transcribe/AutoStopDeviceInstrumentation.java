package dev.notune.transcribe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Spinner;

import java.io.File;
import java.nio.file.Files;

/** Finite release-APK smoke test; never opens a saved transcript or dictionary. */
public final class AutoStopDeviceInstrumentation extends Instrumentation {
    private Activity current;
    private boolean failFixture;
    private boolean callerAudio;
    private boolean fileDecoder;
    private boolean modelFixture;
    private boolean fileTranscription;
    private boolean preparedWindows;
    private static final class InjectedFixtureFailure extends Exception {}

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        failFixture = arguments != null && "true".equals(arguments.getString("fixture_failure"));
        fileTranscription = arguments != null && "true".equals(arguments.getString("file_transcription"));
        modelFixture = arguments != null && "true".equals(arguments.getString("model_fixture"));
        fileDecoder = arguments != null && "true".equals(arguments.getString("file_decoder"));
        callerAudio = arguments != null && "true".equals(arguments.getString("caller_audio"));
        preparedWindows = arguments != null && "true".equals(arguments.getString("prepared_windows"));
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        result.putBoolean("caller_audio_ran", false);
        File directory = getTargetContext().getFilesDir();
        File delayFile = new File(directory, AutoStopDelay.FILE_NAME);
        File enabledFile = new File(directory, "auto_stop");
        byte[] savedDelay = null;
        boolean hadDelay = delayFile.exists();
        boolean wasEnabled = enabledFile.exists();
        int code = Activity.RESULT_OK;
        boolean settingsTouched = false;
        boolean injectedFailureCaught = false;
        try {
            if (hadDelay) savedDelay = Files.readAllBytes(delayFile.toPath());
            check(savedDelay == null || savedDelay.length <= 16, "unexpected delay fixture");
            if (modelFixture) {
                check(getTargetContext().getPackageName().equals("app.offlinespeechtotext.validation"),
                        "model fixture requires isolated validation package");
                File staged = new File(getTargetContext().getCacheDir(), "validation-model.gguf");
                try {
                    try (java.io.InputStream input = getContext().getAssets()
                            .open(DefaultModelContract.FILE_NAME)) {
                        Files.copy(input, staged.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    ModelInstaller.installVerified(directory, staged,
                            ModelCatalog.findByFileName(DefaultModelContract.FILE_NAME));
                    result.putBoolean("verified_model_fixture", true);
                } finally { Files.deleteIfExists(staged.toPath()); }
            }
            if (fileDecoder) { FileDecoderDeviceTest.run(this); result.putBoolean("file_decoder_ran", true); }
            testMainThreadFailureHandling();
            testDictionaryFiles();
            testDiagnosticEvidence();
            if (callerAudio) {
                testCallerDiagnostics(directory);
                result.putBoolean("caller_audio_ran", true);
            }
            settingsTouched = true;
            Files.deleteIfExists(enabledFile.toPath());
            AutoStopDelay.set(directory, 2000);
            launchMain();
            result.putString("stage", "microphone meter");
            testMicLevelAnimation();
            if (preparedWindows) {
                long[] evidence = PreparedWindowsDeviceTest.run(this, current);
                result.putLong("equivalent_prepared_windows", evidence[1]);
                result.putLong("deferred_decode_ms", evidence[2]);
                result.putLong("early_decode_ms", evidence[3]);
                result.putLong("prepared_stop_decode_ms", evidence[4]);
            }
            result.putString("stage", "troubleshooting controls");
            testTroubleshootingControls(directory);
            runSafelyOnMainSync(() -> {
                current.findViewById(R.id.btn_more_settings).performClick();
                check(current.findViewById(R.id.auto_stop_delay_controls).getVisibility() == View.GONE,
                        "delay visible while disabled");
                ((CompoundButton) current.findViewById(R.id.switch_auto_stop)).setChecked(true);
                check(current.findViewById(R.id.auto_stop_delay_controls).getVisibility() == View.VISIBLE,
                        "delay hidden while enabled");
            });
            for (int position = 0; position < 3; position++) {
                final int choice = position;
                runSafelyOnMainSync(() -> ((Spinner) current.findViewById(R.id.spinner_auto_stop_delay))
                        .setSelection(choice));
                waitForIdleSync();
                check(AutoStopDelay.get(directory) == AutoStopDelay.atPosition(choice),
                        "displayed choice not persisted");
            }
            runSafelyOnMainSync(() -> ((CompoundButton) current.findViewById(R.id.switch_auto_stop))
                    .setChecked(false));
            check(AutoStopDelay.get(directory) == 5000, "toggle erased choice");
            closeCurrent();
            launchMain();
            runSafelyOnMainSync(() -> {
                current.findViewById(R.id.btn_more_settings).performClick();
                check(((Spinner) current.findViewById(R.id.spinner_auto_stop_delay))
                        .getSelectedItemPosition() == 2, "activity restart lost choice");
                check(current.findViewById(R.id.auto_stop_delay_controls).getVisibility() == View.GONE,
                        "activity restart enabled delay unexpectedly");
                ((CompoundButton) current.findViewById(R.id.switch_auto_stop)).setChecked(true);
            });
            closeCurrent();
            result.putString("stage", "partial file recovery");
            // A failed file must leave already recovered text available to copy.
            current = startActivitySync(new Intent(getTargetContext(), TranscribeFileActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runSafelyOnMainSync(() -> {
                TranscribeFileActivity file = (TranscribeFileActivity) current;
                file.onPartialText("Synthetic partial transcript");
                file.onStatusUpdate("Error: synthetic file failure");
                check(file.findViewById(R.id.btn_copy).getVisibility() == View.VISIBLE,
                        "file error hid recovered partial text copy");
                check(((android.widget.TextView) file.findViewById(R.id.txt_result)).getText()
                        .toString().equals("Synthetic partial transcript"),
                        "file error erased recovered partial text");
            });
            closeCurrent();
            if (fileTranscription) {
                result.putString("stage", "shared-file native transcription");
                FileTranscriptionDeviceTest.run(this);
                result.putBoolean("file_transcription_ran", true);
            }
            result.putString("stage", "popup JNI");
            // Exercise the changed Java/JNI popup path without running inference
            // or modifying the IME's existing private recovery record.
            Intent popup = new Intent(getTargetContext(), RecognizeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            current = startActivitySync(popup);
            SystemClock.sleep(400);
            runSafelyOnMainSync(() -> {
                check(current.findViewById(R.id.btn_close) != null, "popup did not launch");
                current.findViewById(R.id.btn_close).performClick();
            });
            waitForIdleSync();
            current = null;
            result.putString("checks", (preparedWindows ? "71s same-public-audio prepared/deferred production text equivalence; " : "") + (callerAudio ? "caller audio benchmark/model, EOF/Stop equivalence, audio/no-match errors; " : "") + "isolated dictionary persistence, corrupt UTF-8, atomic save; content-free diagnostic statuses/version/errors; detailed consent warning/cancel/confirm/disable; delay visibility, choices, persistence, restart, popup JNI/cancel");
            result.putString("privacy", "no saved transcript/dictionary collection; isolated synthetic fixtures removed; original settings restored");
        } catch (Throwable failure) {
            injectedFailureCaught = failure instanceof InjectedFixtureFailure;
            code = Activity.RESULT_CANCELED;
            // Fixed failure reasons above contain no input, transcript, or path.
            result.putString("failure_type", failure.getClass().getSimpleName());
            if (failure instanceof AssertionError) result.putString("failure", failure.getMessage());
            java.io.StringWriter trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            result.putString("failure_stack", trace.toString());
        } finally {
            try {
                closeCurrent();
                if (settingsTouched) {
                    if (hadDelay) Files.write(delayFile.toPath(), savedDelay);
                    else Files.deleteIfExists(delayFile.toPath());
                    if (wasEnabled) enabledFile.createNewFile();
                    else Files.deleteIfExists(enabledFile.toPath());
                }
                if (failFixture && injectedFailureCaught) {
                    check(!settingsTouched && code == Activity.RESULT_CANCELED,
                            "failure injection did not run before mutation");
                    check(delayFile.exists() == hadDelay && enabledFile.exists() == wasEnabled,
                            "failed fixture changed setting presence");
                    if (hadDelay) check(java.util.Arrays.equals(savedDelay,
                            Files.readAllBytes(delayFile.toPath())), "failed fixture changed setting bytes");
                    code = Activity.RESULT_OK;
                    result.clear();
                    result.putString("checks", "injected storage-fixture failure preserves original settings");
                }
            } catch (Throwable cleanup) {
                code = Activity.RESULT_CANCELED;
                result.putString("cleanup", "settings restoration failed");
            }
        }
        finish(code, result);
    }

    // Keep UI assertion failures on the test thread so onStart's finally can
    // restore settings instead of Android terminating the instrumented process.
    private void runSafelyOnMainSync(Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        super.runOnMainSync(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw new AssertionError("main-thread test action failed", failure.get());
    }

    private void testMainThreadFailureHandling() {
        boolean restored = false;
        boolean caught = false;
        try {
            try {
                runSafelyOnMainSync(() -> { throw new AssertionError("injected UI assertion"); });
            } finally {
                restored = true;
            }
        } catch (AssertionError expected) {
            caught = expected.getCause() instanceof AssertionError;
        }
        check(caught && restored, "UI failure escaped test-thread cleanup");
    }

    private void testMicLevelAnimation() throws Exception {
        java.lang.reflect.Field animatorField = MicLevelView.class.getDeclaredField("animator");
        java.lang.reflect.Field targetField = MicLevelView.class.getDeclaredField("target");
        animatorField.setAccessible(true);
        targetField.setAccessible(true);
        runSafelyOnMainSync(() -> {
            android.view.ViewGroup root = (android.view.ViewGroup) current.getWindow().getDecorView();
            MicLevelView meter = new MicLevelView(current);
            root.addView(meter, new android.view.ViewGroup.LayoutParams(32, 32));
            try {
                android.animation.ValueAnimator original =
                        (android.animation.ValueAnimator) animatorField.get(meter);
                for (int i = 0; i < 100; i++) meter.setLevel((i % 10) / 10f);
                check(animatorField.get(meter) == original, "meter allocated repeated animators");
                meter.setLevel(Float.NaN);
                check(targetField.getFloat(meter) == 0f, "nonfinite meter target");
                meter.setLevel(Float.POSITIVE_INFINITY);
                check(targetField.getFloat(meter) == 1f, "meter upper clamp lost");
                meter.setLevel(-1f);
                check(targetField.getFloat(meter) == 0f, "meter lower clamp lost");
                root.removeView(meter);
                check(!original.isStarted(), "detached meter keeps animating");
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            } finally {
                if (meter.getParent() == root) root.removeView(meter);
            }
        });
    }

    private void testDictionaryFiles() throws Exception {
        java.nio.file.Path root = Files.createTempDirectory(getTargetContext().getCacheDir().toPath(),
                "dictionary-device-");
        try {
            CustomTerms.write(root.toFile(), "wi-fi\nO’Reilly\n");
            check(CustomTerms.read(root.toFile()).terms.size() == 2, "dictionary save/read failed");
            PersonalCorrections.write(root.toFile(), "sample phrase => SamplePhrase\n");
            PersonalCorrections.setEnabled(root.toFile(), true);
            check(PersonalCorrections.reconcileEnabled(root.toFile()), "valid rules not enabled");
            byte[] corrupt = {(byte) 0xFF};
            Files.write(root.resolve(CustomTerms.FILE_NAME), corrupt);
            try {
                CustomTerms.read(root.toFile());
                throw new AssertionError("corrupt UTF-8 accepted");
            } catch (java.nio.charset.CharacterCodingException expected) {}
            check(java.util.Arrays.equals(corrupt, Files.readAllBytes(root.resolve(CustomTerms.FILE_NAME))),
                    "corrupt bytes modified");
            CustomTerms.write(root.toFile(), "Restored");
            check(CustomTerms.read(root.toFile()).terms.get(0).equals("Restored"), "replacement failed");
            if (failFixture) throw new InjectedFixtureFailure();
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(root)) {
                for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files::iterator) {
                    Files.delete(file);
                }
            }
            Files.delete(root);
        }
    }

    private void testDiagnosticEvidence() throws Exception {
        java.nio.file.Path root = Files.createTempDirectory(getTargetContext().getCacheDir().toPath(),
                "diagnostic-device-");
        try {
            DiagnosticLog.recognizerError(root.toFile(), 9);
            check(!Files.exists(root.resolve("diagnostic.log")), "disabled error logging wrote a file");
            DiagnosticLog.setEnabled(root.toFile(), true);
            check(!DiagnosticLog.isTroubleshootingEnabled(root.toFile()), "basic consent enabled detailed mode");
            DiagnosticLog.setTroubleshootingEnabled(root.toFile(), true);
            DiagnosticLog.troubleshootingEvent(root.toFile(), "session",
                    "process=ime,session_id=12,phase=capture,reason=start,elapsed_ms=0");
            DiagnosticLog.troubleshootingEvent(root.toFile(), "resource",
                    "process=ime,session_id=12,cpu_ms=34,battery_pct=99,power=battery,thermal=0");
            DiagnosticLog.event(root.toFile(), "ime_start",
                    "process=ime,version_code=" + BuildConfig.VERSION_CODE);
            DiagnosticLog.event(root.toFile(), "ime_state",
                    "state=" + NativeStatus.diagnosticLabel("Listening..."));
            DiagnosticLog.event(root.toFile(), "ime_state",
                    "state=" + NativeStatus.diagnosticLabel("Canceled"));
            DiagnosticLog.recognizerError(root.toFile(), 9);
            DiagnosticLog.recognitionOutcome(root.toFile(), 2, 1600);
            DiagnosticLog.event(root.toFile(), "recognition_outcome", "outcome=private_fixture");
            DiagnosticLog.event(root.toFile(), "error_code", "message=private_fixture");
            check(DiagnosticLog.awaitIdleForTest(5_000), "diagnostic fixture did not drain");
            String snapshot = new String(DiagnosticLog.snapshot(root.toFile()),
                    java.nio.charset.StandardCharsets.UTF_8);
            check(snapshot.contains("version_code=" + BuildConfig.VERSION_CODE), "build marker absent");
            check(snapshot.contains("state=recording") && snapshot.contains("state=cancelled"),
                    "capture diagnostic labels absent");
            check(snapshot.contains("code=E_RECOGNIZER_9"), "recognizer error evidence absent");
            check(snapshot.contains("outcome=empty_decode,audio_ms=1600"), "recognition outcome evidence absent");
            check(!snapshot.contains("private_fixture"), "unapproved diagnostic content accepted");
            check(snapshot.contains("cpu_ms=34"), "detailed fixed metadata missing");
            check(snapshot.contains("phase=capture,reason=start,elapsed_ms=0"), "session correlation event missing");
            DiagnosticLog.setEnabled(root.toFile(), false);
            check(!DiagnosticLog.isTroubleshootingEnabled(root.toFile()), "disable did not revoke detailed consent");
        } finally {
            DiagnosticLog.setEnabled(root.toFile(), false);
            check(DiagnosticLog.awaitIdleForTest(5_000), "diagnostic fixture cleanup did not drain");
            try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(root)) {
                for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files::iterator) {
                    Files.delete(file);
                }
            }
            Files.delete(root);
        }
    }

    private void testTroubleshootingControls(File directory) throws Exception {
        File lease = new File(directory, "diagnostic_troubleshooting");
        boolean wasEnabled = DiagnosticLog.isEnabled(directory);
        byte[] savedLease = lease.isFile() && lease.length() == 16 ? Files.readAllBytes(lease.toPath()) : null;
        check(!lease.exists() || savedLease != null, "unexpected detailed consent fixture");
        java.lang.reflect.Field controlsField = MainActivity.class.getDeclaredField("troubleshootingControls");
        controlsField.setAccessible(true);
        TroubleshootingControls controls = (TroubleshootingControls) controlsField.get(current);
        java.lang.reflect.Field warningField = TroubleshootingControls.class.getDeclaredField("warning");
        warningField.setAccessible(true);
        try {
            DiagnosticLog.setEnabled(directory, true);
            DiagnosticLog.setTroubleshootingEnabled(directory, false);
            runSafelyOnMainSync(() -> {
                controls.refresh();
                ((CompoundButton) current.findViewById(R.id.switch_troubleshooting)).setChecked(true);
                check(!DiagnosticLog.isTroubleshootingEnabled(directory), "switch bypassed consent warning");
            });
            android.app.Dialog warning = (android.app.Dialog) warningField.get(controls);
            runSafelyOnMainSync(() -> warning.findViewById(android.R.id.button2).performClick());
            // AlertController posts button callbacks to the main message queue.
            waitForIdleSync();
            check(!DiagnosticLog.isTroubleshootingEnabled(directory), "Cancel enabled detailed mode");
            runSafelyOnMainSync(() -> ((CompoundButton) current.findViewById(R.id.switch_troubleshooting)).setChecked(true));
            android.app.Dialog confirmation = (android.app.Dialog) warningField.get(controls);
            runSafelyOnMainSync(() -> confirmation.findViewById(android.R.id.button1).performClick());
            // AlertController posts button callbacks to the main message queue.
            waitForIdleSync();
            check(DiagnosticLog.isTroubleshootingEnabled(directory), "explicit confirmation did not enable");
            runSafelyOnMainSync(() -> check(current.findViewById(R.id.troubleshooting_banner).getVisibility() == View.VISIBLE,
                    "active warning banner missing"));
            long now = System.currentTimeMillis();
            Files.write(lease.toPath(), java.nio.ByteBuffer.allocate(16)
                    .putLong(now - DiagnosticLog.TROUBLESHOOTING_DURATION_MS - 1).putLong(now - 1).array());
            runSafelyOnMainSync(() -> {
                controls.refresh();
                check(current.findViewById(R.id.troubleshooting_banner).getVisibility() == View.GONE,
                        "expired warning banner remained visible");
                check(!((CompoundButton) current.findViewById(R.id.switch_troubleshooting)).isChecked(),
                        "expired detailed mode remained checked");
            });
            check(!lease.exists(), "expired consent was not removed");
            runSafelyOnMainSync(() -> ((CompoundButton) current.findViewById(R.id.switch_troubleshooting)).setChecked(true));
            check(!DiagnosticLog.isTroubleshootingEnabled(directory), "renewal bypassed consent warning");
            android.app.Dialog renewal = (android.app.Dialog) warningField.get(controls);
            check(renewal != confirmation, "renewal reused a dismissed warning");
            runSafelyOnMainSync(() -> check(renewal != null && renewal.isShowing(), "renewal warning not shown"));
            runSafelyOnMainSync(() -> renewal.findViewById(android.R.id.button1).performClick());
            // AlertController posts button callbacks to the main message queue.
            waitForIdleSync();
            check(DiagnosticLog.isTroubleshootingEnabled(directory), "renewal did not enable detailed mode");
            runSafelyOnMainSync(() -> check(current.findViewById(R.id.troubleshooting_banner).getVisibility() == View.VISIBLE,
                    "renewal did not restore warning banner"));
            runSafelyOnMainSync(() -> ((CompoundButton) current.findViewById(R.id.switch_troubleshooting)).setChecked(false));
            check(!DiagnosticLog.isTroubleshootingEnabled(directory), "toggle off did not revoke");
            runSafelyOnMainSync(() -> ((CompoundButton) current.findViewById(R.id.switch_troubleshooting)).setChecked(true));
            check(!DiagnosticLog.isTroubleshootingEnabled(directory), "day choice bypassed consent warning");
            android.app.Dialog dayWarning = (android.app.Dialog) warningField.get(controls);
            runSafelyOnMainSync(() -> dayWarning.findViewById(android.R.id.button3).performClick());
            waitForIdleSync();
            check(DiagnosticLog.isTroubleshootingEnabled(directory), "day confirmation did not enable");
            java.nio.ByteBuffer dayLease = java.nio.ByteBuffer.wrap(Files.readAllBytes(lease.toPath()));
            check(dayLease.getLong(8) - dayLease.getLong(0) == DiagnosticLog.TROUBLESHOOTING_DAY_MS,
                    "day confirmation stored the wrong expiry");
            runSafelyOnMainSync(() -> ((CompoundButton) current.findViewById(R.id.switch_troubleshooting)).setChecked(false));
            check(!DiagnosticLog.isTroubleshootingEnabled(directory), "day toggle off did not revoke");
        } finally {
            DiagnosticLog.setEnabled(directory, wasEnabled);
            if (savedLease != null) Files.write(lease.toPath(), savedLease);
            else Files.deleteIfExists(lease.toPath());
            runSafelyOnMainSync(controls::refresh);
        }
    }

    private void testCallerDiagnostics(File directory) throws Exception {
        File lease = new File(directory, "diagnostic_troubleshooting");
        boolean basic = DiagnosticLog.isEnabled(directory);
        byte[] original = lease.isFile() && lease.length() == 16 ? Files.readAllBytes(lease.toPath()) : null;
        check(!lease.exists() || original != null, "unexpected caller consent fixture");
        try {
            DiagnosticLog.setEnabled(directory, true);
            DiagnosticLog.setTroubleshootingEnabled(directory, true);
            long start = System.currentTimeMillis();
            CallerAudioProviderDeviceTest.run(this);
            check(DiagnosticLog.awaitIdleForTest(5000), "caller diagnostics did not drain");
            String log = new String(DiagnosticLog.snapshot(directory), java.nio.charset.StandardCharsets.UTF_8);
            boolean pipeline = false, session = false, resources = false;
            for (String line : log.split("\n")) {
                int space = line.indexOf(' ');
                if (space <= 0) continue;
                try {
                    if (Long.parseLong(line.substring(0, space)) < start) continue;
                } catch (NumberFormatException oldOrIncompleteLine) { continue; }
                if (!line.contains("route=caller_audio")) continue;
                pipeline |= line.contains(" pipeline ") && line.contains("stop_wait_ms=") && line.contains("session_id=");
                session |= line.contains("phase=capture,reason=start,elapsed_ms=");
                resources |= line.contains(" resource ") && line.contains("cpu_ms=") && line.contains("thermal=");
            }
            check(pipeline && session && resources, "actual provider JNI/session/resource diagnostics absent");
        } finally {
            DiagnosticLog.setEnabled(directory, basic);
            if (original == null) Files.deleteIfExists(lease.toPath());
            else Files.write(lease.toPath(), original);
        }
    }

    private void launchMain() {
        current = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
    }

    private void closeCurrent() {
        if (current == null) return;
        runSafelyOnMainSync(() -> current.finish());
        waitForIdleSync();
        current = null;
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
