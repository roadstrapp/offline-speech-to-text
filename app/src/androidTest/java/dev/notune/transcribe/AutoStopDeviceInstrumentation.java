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
    private String qualificationModel;
    private boolean qualificationBilingual;
    private boolean installedModelsUi;
    private boolean qualifyPrepared;
    private boolean donationUi;
    private boolean redesignUi;
    private boolean seedUiUpgrade;
    private boolean captureCapability;
    private boolean qualifySetup;
    private boolean fileApi;
    private int fileApiMinutes;
    private int callerAudioMinutes;
    private int callerAudioPace;
    private boolean bluetoothMic;
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
        qualifyPrepared = arguments != null && "true".equals(arguments.getString("qualify_prepared"));
        donationUi = arguments != null && "true".equals(arguments.getString("donation_ui"));
        redesignUi = arguments != null && "true".equals(arguments.getString("redesign_ui"));
        seedUiUpgrade = arguments != null && "true".equals(arguments.getString("seed_ui_upgrade"));
        bluetoothMic = arguments != null && "true".equals(arguments.getString("bluetooth_mic"));
        installedModelsUi = arguments != null && "true".equals(arguments.getString("installed_models_ui"));
        qualificationBilingual = arguments != null && "true".equals(arguments.getString("qualify_bilingual"));
        qualificationModel = arguments == null ? null : arguments.getString("qualify_model");
        fileApi = arguments != null && "true".equals(arguments.getString("file_api"));
        fileApiMinutes = 25;
        if (arguments != null && arguments.getString("file_api_minutes") != null) {
            fileApiMinutes = Integer.parseInt(arguments.getString("file_api_minutes"));
        }
        if (arguments != null && arguments.getString("caller_audio_minutes") != null) {
            callerAudioMinutes = Integer.parseInt(arguments.getString("caller_audio_minutes"));
            String pace = arguments.getString("caller_audio_pace");
            callerAudioPace = pace == null ? 0 : Integer.parseInt(pace);
        }
        qualifySetup = arguments != null && "true".equals(arguments.getString("qualify_setup"));
        captureCapability = arguments != null && "true".equals(arguments.getString("capture_capability"));
        start();
    }

    @Override
    public void onStart() {
        if (qualifySetup) { ModelQualificationDeviceTest.runSetup(this); return; }
        if (captureCapability) { AudioCaptureCapabilityDeviceTest.run(this); return; }
        if (qualifyPrepared) { ModelQualificationDeviceTest.runPrepared(this); return; }
        if (fileApi) { FileApiDeviceTest.run(this, fileApiMinutes); return; }
        if (callerAudioMinutes > 0) {
            FileApiDeviceTest.runCallerAudio(this, callerAudioMinutes, callerAudioPace);
            return;
        }
        if (bluetoothMic) { testBluetoothMic(); return; }
        if (donationUi) { testDonationUi(); return; }
        if (qualificationModel != null) { ModelQualificationDeviceTest.run(this, qualificationModel, qualificationBilingual); return; }
        if (installedModelsUi) { testInstalledModelsUi(); return; }
        if (seedUiUpgrade) { seedUiUpgrade(); return; }
        if (redesignUi) { testRedesignUi(); return; }
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
                ((com.google.android.material.bottomnavigation.BottomNavigationView)
                        current.findViewById(R.id.main_navigation))
                        .setSelectedItemId(R.id.navigation_settings);
                current.findViewById(R.id.nav_recording).performClick();
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
                ((com.google.android.material.bottomnavigation.BottomNavigationView)
                        current.findViewById(R.id.main_navigation))
                        .setSelectedItemId(R.id.navigation_settings);
                current.findViewById(R.id.nav_recording).performClick();
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

    private void testBluetoothMic() {
        Bundle result;
        int code = Activity.RESULT_OK;
        try {
            result = BluetoothMicDeviceTest.run(this);
        } catch (Throwable failure) {
            code = Activity.RESULT_CANCELED;
            result = new Bundle();
            result.putString("failure_type", failure.getClass().getSimpleName());
            if (failure instanceof AssertionError) result.putString("failure", failure.getMessage());
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
        if (failure.get() != null) throw new AssertionError("main-thread test action failed: " + failure.get(), failure.get());
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

    private void chooseTroubleshootingOption(android.app.Dialog dialog, int choice) {
        android.widget.ListView list = dialog.findViewById(current.getResources().getIdentifier(
                "select_dialog_listview", "id", current.getPackageName()));
        check(list != null && list.getHeaderViewsCount() == 1, "warning header missing");
        check(!list.getAdapter().isEnabled(0), "warning header must not be selectable");
        int[] ids = {R.string.troubleshooting_enable, R.string.troubleshooting_enable_day,
                R.string.troubleshooting_enable_forever, android.R.string.cancel};
        for (int index = 0; index < ids.length; index++) {
            check(current.getString(ids[index]).contentEquals((CharSequence) list.getAdapter().getItem(index + 1)),
                    "troubleshooting choices are not in the requested order");
        }
        list.performItemClick(null, choice + 1, list.getAdapter().getItemId(choice + 1));
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
            runSafelyOnMainSync(() -> chooseTroubleshootingOption(warning, 3));
            // AlertController posts button callbacks to the main message queue.
            waitForIdleSync();
            check(!DiagnosticLog.isTroubleshootingEnabled(directory), "Cancel enabled detailed mode");
            runSafelyOnMainSync(() -> ((CompoundButton) current.findViewById(R.id.switch_troubleshooting)).setChecked(true));
            android.app.Dialog confirmation = (android.app.Dialog) warningField.get(controls);
            runSafelyOnMainSync(() -> chooseTroubleshootingOption(confirmation, 0));
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
            runSafelyOnMainSync(() -> chooseTroubleshootingOption(renewal, 0));
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
            runSafelyOnMainSync(() -> chooseTroubleshootingOption(dayWarning, 1));
            waitForIdleSync();
            check(DiagnosticLog.isTroubleshootingEnabled(directory), "day confirmation did not enable");
            java.nio.ByteBuffer dayLease = java.nio.ByteBuffer.wrap(Files.readAllBytes(lease.toPath()));
            check(dayLease.getLong(8) - dayLease.getLong(0) == DiagnosticLog.TROUBLESHOOTING_DAY_MS,
                    "day confirmation stored the wrong expiry");
            runSafelyOnMainSync(() -> ((CompoundButton) current.findViewById(R.id.switch_troubleshooting)).setChecked(false));
            check(!DiagnosticLog.isTroubleshootingEnabled(directory), "day toggle off did not revoke");
            runSafelyOnMainSync(() -> ((CompoundButton) current.findViewById(R.id.switch_troubleshooting)).setChecked(true));
            android.app.Dialog foreverWarning = (android.app.Dialog) warningField.get(controls);
            runSafelyOnMainSync(() -> chooseTroubleshootingOption(foreverWarning, 2));
            waitForIdleSync();
            check(DiagnosticLog.isTroubleshootingEnabled(directory), "forever confirmation did not enable");
            check(java.nio.ByteBuffer.wrap(Files.readAllBytes(lease.toPath())).getLong(8) == Long.MAX_VALUE,
                    "forever confirmation did not persist indefinite expiry");
            runSafelyOnMainSync(() -> ((CompoundButton) current.findViewById(R.id.switch_troubleshooting)).setChecked(false));
            check(!DiagnosticLog.isTroubleshootingEnabled(directory), "forever toggle off did not revoke");

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
            boolean timing = false, session = false, resources = false;
            for (String line : log.split("\n")) {
                int space = line.indexOf(' ');
                if (space <= 0) continue;
                try {
                    if (Long.parseLong(line.substring(0, space)) < start) continue;
                } catch (NumberFormatException oldOrIncompleteLine) { continue; }
                timing |= line.contains(" timing ") && line.contains("process=service,audio_ms=") && line.contains("duration_ms=");
                if (!line.contains("route=caller_audio")) continue;
                session |= line.contains("phase=capture,reason=start,elapsed_ms=");
                resources |= line.contains(" resource ") && line.contains("cpu_ms=") && line.contains("thermal=");
            }
            check(timing && session && resources, "actual provider JNI/session/resource diagnostics absent");
        } finally {
            DiagnosticLog.setEnabled(directory, basic);
            if (original == null) Files.deleteIfExists(lease.toPath());
            else Files.write(lease.toPath(), original);
        }
    }

    private void testDonationUi() {
        Bundle result = new Bundle();
        java.util.concurrent.atomic.AtomicReference<Intent> launched = new java.util.concurrent.atomic.AtomicReference<>();
        ActivityMonitor monitor = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                if (Intent.ACTION_VIEW.equals(intent.getAction())
                        && intent.getData() != null && "lightning".equals(intent.getData().getScheme())) {
                    launched.set(new Intent(intent));
                    return new ActivityResult(Activity.RESULT_CANCELED, null);
                }
                return null;
            }
        };
        int code = Activity.RESULT_OK;
        try {
            check(getTargetContext().getPackageName().equals("app.offlinespeechtotext.validation"),
                    "donation test requires isolated fixture");
            launchMain();
            addMonitor(monitor);
            runSafelyOnMainSync(() -> {
                ((com.google.android.material.bottomnavigation.BottomNavigationView)
                        current.findViewById(R.id.main_navigation)).setSelectedItemId(R.id.navigation_settings);
                current.findViewById(R.id.nav_about).performClick();
                check(current.findViewById(R.id.btn_donate).isShown(), "Donate hidden in About");
                current.findViewById(R.id.btn_donate).performClick();
            });
            Intent intent = launched.get();
            check(intent != null, "Donate did not dispatch Lightning wallet intent");
            check(intent.getData().toString().equals("lightning:npub1jg2qmt3rxjksq65v3nk7dq6yaz2c4uf3qt9epdhxwms2xwd47j2s38a228@npub.cash"),
                    "Donate used a different project address");
            result.putBoolean("donation_ui", true);
            result.putString("scope", "visible Donate button dispatches exact app Lightning address; wallet intent intercepted, no payment");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            result.putString("failure", error.toString());
        } finally {
            removeMonitor(monitor);
            closeCurrent();
        }
        finish(code, result);
    }

    private void seedUiUpgrade() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            check(getTargetContext().getPackageName().equals("app.offlinespeechtotext.validation"),
                    "upgrade seed requires isolated fixture");
            CustomTerms.write(getTargetContext().getFilesDir(), "Synthetic Upgrade");
            AutoStopDelay.set(getTargetContext().getFilesDir(), 5000);
            ThemePrefs.setMode(getTargetContext(), androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
            result.putBoolean("seed_ui_upgrade", true);
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            result.putString("failure", error.toString());
        }
        finish(code, result);
    }

    private void testInstalledModelsUi() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        File models = new File(getTargetContext().getFilesDir(), "models");
        File first = new File(models, "Synthetic One.gguf");
        File second = new File(models, "Synthetic Two.gguf");
        boolean ownsInventoryFixtures = false;
        java.util.concurrent.atomic.AtomicReference<ModelDownloadTask.Listener> fakeDownload = new java.util.concurrent.atomic.AtomicReference<>();
        ModelDownloadLifecycle[] downloadOwner = new ModelDownloadLifecycle[1];
        try {
            check(getTargetContext().getPackageName().equals("app.offlinespeechtotext.validation"),
                    "inventory fixtures require isolated package");
            check(!first.exists() && !second.exists(), "inventory fixture already exists");
            ownsInventoryFixtures = true;
            models.mkdirs();
            current = startActivitySync(new Intent(getTargetContext(), ModelsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            java.lang.reflect.Method refresh = ModelsActivity.class.getDeclaredMethod("refreshList");
            refresh.setAccessible(true);
            runSafelyOnMainSync(() -> {
                check(current.findViewById(R.id.card_recommendation).isShown(), "empty inventory needs recommendation");
                check(!current.findViewById(R.id.card_installed_models).isShown(), "empty inventory shown as installed");
            });
            // Inventory-only placeholders: explicitly unverified, never selected or loaded.
            Files.write(first.toPath(), new byte[]{'G', 'G', 'U', 'F'});
            runSafelyOnMainSync(() -> {
                try { refresh.invoke(current); } catch (Exception error) { throw new AssertionError(error); }
                check(!current.findViewById(R.id.card_recommendation).isShown(), "installed inventory still recommends download");
                check(current.findViewById(R.id.card_installed_models).isShown(), "installed inventory missing from default page");
                check(!current.findViewById(R.id.btn_models_installed).isShown(), "redundant installed shortcut remains");
            });
            waitForIdleSync();
            android.graphics.Bitmap inventoryScreen = getUiAutomation().takeScreenshot();
            check(inventoryScreen != null, "inventory screenshot unavailable");
            try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                check(inventoryScreen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output),
                        "inventory screenshot failed");
                result.putString("inventory_png", android.util.Base64.encodeToString(
                        output.toByteArray(), android.util.Base64.NO_WRAP));
            } finally { inventoryScreen.recycle(); }
            Files.write(second.toPath(), new byte[]{'G', 'G', 'U', 'F'});
            Files.delete(first.toPath());
            runSafelyOnMainSync(() -> {
                try { refresh.invoke(current); } catch (Exception error) { throw new AssertionError(error); }
                check(!current.findViewById(R.id.card_recommendation).isShown(), "remaining model lost default inventory view");
            });
            Files.delete(second.toPath());
            runSafelyOnMainSync(() -> {
                try { refresh.invoke(current); } catch (Exception error) { throw new AssertionError(error); }
                check(current.findViewById(R.id.card_recommendation).isShown(), "last removal did not restore recommendation");
                check(!current.findViewById(R.id.card_installed_models).isShown(), "last removal left stale inventory");
            });
            runSafelyOnMainSync(() -> {
                com.google.android.material.appbar.MaterialToolbar toolbar = current.findViewById(R.id.toolbar);
                check(toolbar.getMenu().findItem(R.id.btn_models_preferences).isVisible(), "preferences menu hidden");
                check(toolbar.getMenu().performIdentifierAction(R.id.btn_models_preferences, 0), "preferences menu did not handle action");
                check(current.findViewById(R.id.card_model_language).isShown(), "preferences route lost language controls");
                ((androidx.activity.ComponentActivity) current).getOnBackPressedDispatcher().onBackPressed();
                check(toolbar.getMenu().findItem(R.id.btn_models_browse).isVisible(), "other-models menu hidden");
                check(toolbar.getMenu().performIdentifierAction(R.id.btn_models_browse, 0), "catalog menu did not handle action");
                check(current.findViewById(R.id.card_download_catalog).isShown(), "other models route hidden");
                android.view.ViewGroup defaultCatalog = current.findViewById(R.id.download_catalog);
                int defaultRows = 0;
                for (int i = 0; i < defaultCatalog.getChildCount(); i++) {
                    if (defaultCatalog.getChildAt(i).findViewById(R.id.btn_download_model) != null) defaultRows++;
                }
                check(defaultRows == 3, "default Parakeet filter must show its three tested variants");
                ((android.widget.Spinner) current.findViewById(R.id.spinner_catalog_family)).setSelection(0);
            });
            waitForIdleSync();
            runSafelyOnMainSync(() -> {
                android.view.ViewGroup catalog = current.findViewById(R.id.download_catalog);
                int modelRows = 0;
                for (int i = 0; i < catalog.getChildCount(); i++) {
                    if (catalog.getChildAt(i).findViewById(R.id.btn_download_model) != null) modelRows++;
                }
                check(modelRows == 11, "curated catalogue must expose eleven models; actual=" + modelRows);
                ((androidx.activity.ComponentActivity) current).getOnBackPressedDispatcher().onBackPressed();
            });
            java.lang.reflect.Field lifecycle = ModelsActivity.class.getDeclaredField("DOWNLOAD_LIFECYCLE");
            lifecycle.setAccessible(true);
            downloadOwner[0] = (ModelDownloadLifecycle) lifecycle.get(null);
            runSafelyOnMainSync(() -> {
                downloadOwner[0].start("parakeet-tdt-0.6b-v3-q8", "catalog", listener -> {
                    fakeDownload.set(listener); listener.onProgress(12, 100); return null;
                });
                check(current.findViewById(R.id.card_transfer).isShown(), "transfer feedback hidden");
                check(!current.findViewById(R.id.card_recommendation).isShown(), "download repeats recommendation action");
                check(((android.widget.TextView) current.findViewById(R.id.text_transfer)).getText()
                        .toString().contains("Parakeet v3 Q8"), "download lost human-readable model identity");
            });
            waitForIdleSync();
            android.graphics.Bitmap transferScreen = getUiAutomation().takeScreenshot();
            check(transferScreen != null, "transfer screenshot unavailable");
            try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                check(transferScreen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output), "transfer screenshot failed");
                result.putString("transfer_png", android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP));
            } finally { transferScreen.recycle(); }
            result.putBoolean("installed_models_ui", true);
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            result.putString("failure", error.toString());
        } finally {
            if (fakeDownload.get() != null) runSafelyOnMainSync(() -> {
                fakeDownload.get().onFailure("Synthetic visual fixture ended");
                downloadOwner[0].clearTerminal();
            });
            closeCurrent();
            try { if (ownsInventoryFixtures) {
                Files.deleteIfExists(first.toPath()); Files.deleteIfExists(second.toPath());
            } }
            catch (Exception error) { code = Activity.RESULT_CANCELED; result.putString("cleanup_failure", error.toString()); }
        }
        finish(code, result);
    }

    private void testRedesignUi() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        File corrections = new File(getTargetContext().getFilesDir(), PersonalCorrections.FILE_NAME);
        boolean ownsCorrections = false;
        try {
            check(getTargetContext().getPackageName().equals("app.offlinespeechtotext.validation"),
                    "UI fixtures require isolated package");
            check(CustomTerms.read(getTargetContext().getFilesDir()).terms.contains("Synthetic Upgrade"),
                    "upgrade lost preferred words");
            check(AutoStopDelay.get(getTargetContext().getFilesDir()) == 5000,
                    "upgrade lost pause setting");
            check(ThemePrefs.getMode(getTargetContext()) == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES,
                    "upgrade lost explicit dark theme");
            launchMain();
            runSafelyOnMainSync(() -> {
                check(current.findViewById(R.id.btn_models).isShown(), "setup model action hidden");
                check(!current.findViewById(R.id.btn_foreground_record).isShown(), "recording promoted on Home");
                check(!current.findViewById(R.id.btn_subs_start).isShown(), "subtitles promoted on Home");
                ((com.google.android.material.bottomnavigation.BottomNavigationView)
                        current.findViewById(R.id.main_navigation)).setSelectedItemId(R.id.navigation_settings);
                current.findViewById(R.id.nav_keyboard_settings).performClick();
                check(current.findViewById(R.id.page_keyboard).isShown(), "keyboard page hidden");
                ((androidx.activity.ComponentActivity) current).getOnBackPressedDispatcher().onBackPressed();
                check(current.findViewById(R.id.page_settings).isShown(), "detail Back skipped Settings");
                ((androidx.activity.ComponentActivity) current).getOnBackPressedDispatcher().onBackPressed();
                check(current.findViewById(R.id.page_home).isShown(), "Settings Back missed Home");
            });
            closeCurrent();
            current = startActivitySync(new Intent(getTargetContext(), ModelsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            java.lang.reflect.Field importField = ModelsActivity.class.getDeclaredField("IMPORT_LIFECYCLE");
            importField.setAccessible(true);
            ModelImportLifecycle imports = (ModelImportLifecycle) importField.get(null);
            java.util.concurrent.atomic.AtomicReference<ModelImportTask.Listener> importListener =
                    new java.util.concurrent.atomic.AtomicReference<>();
            runSafelyOnMainSync(() -> imports.start("synthetic-import.gguf", 100L, importListener::set));
            waitForIdleSync();
            java.lang.reflect.Method refresh = ModelsActivity.class.getDeclaredMethod("refreshList");
            java.lang.reflect.Method recommend = ModelsActivity.class.getDeclaredMethod("updateRecommendation");
            refresh.setAccessible(true); recommend.setAccessible(true);
            runSafelyOnMainSync(() -> {
                try { refresh.invoke(current); recommend.invoke(current); }
                catch (Exception error) { throw new AssertionError(error); }
                check(current.findViewById(R.id.card_transfer).isShown(), "refresh hid import progress");
                check(!current.findViewById(R.id.btn_models_import).isEnabled(), "import shortcut re-enabled");
                check(!current.findViewById(R.id.btn_download_recommended).isEnabled(), "recommended action re-enabled during import");
            });
            runSafelyOnMainSync(() -> importListener.get().onFailure(ModelImportTask.Failure.IMPORT_FAILED));
            waitForIdleSync();
            closeCurrent();
            check(!corrections.exists(), "unexpected preexisting dictionary fixture");
            byte[] invalid = new byte[]{(byte)0xff, (byte)0xfe};
            Files.write(corrections.toPath(), invalid);
            ownsCorrections = true;
            current = startActivitySync(new Intent(getTargetContext(), CustomTermsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runSafelyOnMainSync(() -> {
                current.findViewById(R.id.dictionary_tab_corrections).performClick();
                check(!current.findViewById(R.id.personal_corrections_add).isEnabled(),
                        "invalid saved corrections permit replacement without confirmation");
                ((CompoundButton) current.findViewById(R.id.personal_corrections_enabled)).setChecked(true);
            });
            check(java.util.Arrays.equals(invalid, Files.readAllBytes(corrections.toPath())),
                    "enable overwrote unreadable corrections");
            closeCurrent();
            Files.delete(corrections.toPath());
            current = startActivitySync(new Intent(getTargetContext(), CustomTermsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runSafelyOnMainSync(() -> {
                current.findViewById(R.id.dictionary_tab_corrections).performClick();
                current.findViewById(R.id.personal_corrections_add).performClick();
            });
            java.lang.reflect.Field dialogField = CustomTermsActivity.class.getDeclaredField("correctionDialog");
            dialogField.setAccessible(true);
            androidx.appcompat.app.AlertDialog draft = (androidx.appcompat.app.AlertDialog) dialogField.get(current);
            runSafelyOnMainSync(() -> {
                ((android.widget.EditText) draft.findViewById(R.id.personal_correction_original)).setText("synthetic heard");
                ((android.widget.EditText) draft.findViewById(R.id.personal_correction_replacement)).setText("Synthetic Written");
            });
            ActivityMonitor monitor = addMonitor(CustomTermsActivity.class.getName(), null, false);
            runSafelyOnMainSync(() -> current.recreate());
            Activity restored = waitForMonitorWithTimeout(monitor, 8000);
            removeMonitor(monitor);
            check(restored != null && restored != current, "dictionary recreation not observed");
            current = restored;
            waitForIdleSync();
            androidx.appcompat.app.AlertDialog restoredDraft =
                    (androidx.appcompat.app.AlertDialog) dialogField.get(current);
            runSafelyOnMainSync(() -> {
                check(restoredDraft != null && restoredDraft.isShowing(), "rotation lost correction dialog");
                check(((android.widget.EditText) restoredDraft.findViewById(R.id.personal_correction_original))
                        .getText().toString().equals("synthetic heard"), "rotation lost heard draft");
                restoredDraft.findViewById(android.R.id.button2).performClick();
                check(restoredDraft.isShowing(), "rotation lost unsaved draft discard guard");
            });
            closeCurrent();
            result.putBoolean("redesign_ui", true);
            result.putString("checks", "setup hierarchy; secondary recording/subtitles; nested Back; invalid dictionary bytes preserved on enable");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            result.putString("failure", error.toString());
            java.io.StringWriter trace = new java.io.StringWriter();
            error.printStackTrace(new java.io.PrintWriter(trace));
            result.putString("failure_stack", trace.toString());
        } finally {
            closeCurrent();
            try { if (ownsCorrections) Files.deleteIfExists(corrections.toPath()); }
            catch (Exception error) { code = Activity.RESULT_CANCELED; result.putString("cleanup_failure", error.toString()); }
        }
        finish(code, result);
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
