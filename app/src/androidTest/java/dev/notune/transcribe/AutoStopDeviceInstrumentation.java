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

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        failFixture = arguments != null && "true".equals(arguments.getString("fixture_failure"));
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        File directory = getTargetContext().getFilesDir();
        File delayFile = new File(directory, AutoStopDelay.FILE_NAME);
        File enabledFile = new File(directory, "auto_stop");
        byte[] savedDelay = null;
        boolean hadDelay = delayFile.exists();
        boolean wasEnabled = enabledFile.exists();
        int code = Activity.RESULT_OK;
        boolean settingsTouched = false;
        try {
            if (hadDelay) savedDelay = Files.readAllBytes(delayFile.toPath());
            check(savedDelay == null || savedDelay.length <= 16, "unexpected delay fixture");
            testDictionaryFiles();
            settingsTouched = true;
            Files.deleteIfExists(enabledFile.toPath());
            AutoStopDelay.set(directory, 2000);
            launchMain();
            runOnMainSync(() -> {
                current.findViewById(R.id.btn_more_settings).performClick();
                check(current.findViewById(R.id.auto_stop_delay_controls).getVisibility() == View.GONE,
                        "delay visible while disabled");
                ((CompoundButton) current.findViewById(R.id.switch_auto_stop)).setChecked(true);
                check(current.findViewById(R.id.auto_stop_delay_controls).getVisibility() == View.VISIBLE,
                        "delay hidden while enabled");
            });
            for (int position = 0; position < 3; position++) {
                final int choice = position;
                runOnMainSync(() -> ((Spinner) current.findViewById(R.id.spinner_auto_stop_delay))
                        .setSelection(choice));
                waitForIdleSync();
                check(AutoStopDelay.get(directory) == AutoStopDelay.atPosition(choice),
                        "displayed choice not persisted");
            }
            runOnMainSync(() -> ((CompoundButton) current.findViewById(R.id.switch_auto_stop))
                    .setChecked(false));
            check(AutoStopDelay.get(directory) == 5000, "toggle erased choice");
            closeCurrent();
            launchMain();
            runOnMainSync(() -> {
                current.findViewById(R.id.btn_more_settings).performClick();
                check(((Spinner) current.findViewById(R.id.spinner_auto_stop_delay))
                        .getSelectedItemPosition() == 2, "activity restart lost choice");
                check(current.findViewById(R.id.auto_stop_delay_controls).getVisibility() == View.GONE,
                        "activity restart enabled delay unexpectedly");
                ((CompoundButton) current.findViewById(R.id.switch_auto_stop)).setChecked(true);
            });
            closeCurrent();
            // Exercise the changed Java/JNI popup path without running inference
            // or modifying the IME's existing private recovery record.
            Intent popup = new Intent(getTargetContext(), RecognizeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            current = startActivitySync(popup);
            SystemClock.sleep(400);
            runOnMainSync(() -> {
                check(current.findViewById(R.id.btn_close) != null, "popup did not launch");
                current.findViewById(R.id.btn_close).performClick();
            });
            waitForIdleSync();
            current = null;
            result.putString("checks", "isolated dictionary persistence, corrupt UTF-8, atomic save; delay visibility, choices, persistence, restart, popup JNI/cancel");
            result.putString("privacy", "no saved transcript/dictionary collection; isolated synthetic fixtures removed; original settings restored");
        } catch (Throwable failure) {
            code = Activity.RESULT_CANCELED;
            // Fixed failure reasons above contain no input, transcript, or path.
            result.putString("failure_type", failure.getClass().getSimpleName());
            if (failure instanceof AssertionError) result.putString("failure", failure.getMessage());
        } finally {
            try {
                closeCurrent();
                if (settingsTouched) {
                    if (hadDelay) Files.write(delayFile.toPath(), savedDelay);
                    else Files.deleteIfExists(delayFile.toPath());
                    if (wasEnabled) enabledFile.createNewFile();
                    else Files.deleteIfExists(enabledFile.toPath());
                }
                if (failFixture) {
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

    private void testDictionaryFiles() throws Exception {
        java.nio.file.Path root = Files.createTempDirectory(getTargetContext().getCacheDir().toPath(),
                "dictionary-device-");
        try {
            CustomTerms.write(root.toFile(), "wi-fi\nO’Reilly\n");
            check(CustomTerms.read(root.toFile()).terms.size() == 2, "dictionary save/read failed");
            PersonalCorrections.write(root.toFile(), "white noise => WhiteNoise\n");
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
            if (failFixture) throw new AssertionError("injected storage-fixture failure");
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(root)) {
                for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files::iterator) {
                    Files.delete(file);
                }
            }
            Files.delete(root);
        }
    }

    private void launchMain() {
        current = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
    }

    private void closeCurrent() {
        if (current == null) return;
        runOnMainSync(() -> current.finish());
        waitForIdleSync();
        current = null;
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
