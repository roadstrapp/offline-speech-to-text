package dev.notune.transcribe;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bluetooth microphone routing smoke for the isolated validation package.
 * Opens provider microphone capture through the in-process recognition
 * service with the setting on and off, records only device types, and cancels
 * every capture; no audio, transcript or device address is collected. With no
 * Bluetooth microphone connected the enabled case must fall back to the phone
 * microphone. The original setting is restored.
 */
final class BluetoothMicDeviceTest {
    private static final long CAPTURE_SAMPLE_MS = 2_500L;

    static Bundle run(Instrumentation fixture) throws Exception {
        Context context = fixture.getTargetContext();
        if (!"app.offlinespeechtotext.validation".equals(context.getPackageName())) {
            throw new AssertionError("bluetooth routing test requires the validation package");
        }
        Bundle result = new Bundle();
        AudioManager manager = context.getSystemService(AudioManager.class);
        File marker = new File(context.getFilesDir(), BluetoothMicRoute.MARKER);
        boolean wasEnabled = marker.exists();
        SpeechRecognizer[] recognizer = new SpeechRecognizer[1];
        fixture.runOnMainSync(() -> recognizer[0] = SpeechRecognizer.createSpeechRecognizer(
                context, new ComponentName(context, VoiceRecognitionService.class)));
        try {
            result.putString("communication_before", typeOf(manager.getCommunicationDevice()));
            if (!marker.exists() && !marker.createNewFile()) throw new AssertionError("marker");
            capture(fixture, recognizer[0], manager, result, "enabled");
            Thread.sleep(500);
            check(!BluetoothMicRoute.isRouted(), "route held after cancel");
            result.putString("communication_after_enabled", typeOf(manager.getCommunicationDevice()));

            // The popup exercises the shared voice-session capture used by the
            // keyboard and long recording. On a locked phone it may be stopped
            // (and cancel itself) before routing is sampled; that is reported.
            popup(fixture, manager, result);
            Thread.sleep(500);
            check(!BluetoothMicRoute.isRouted(), "popup route held after close");
            result.putString("communication_after_popup", typeOf(manager.getCommunicationDevice()));

            if (!marker.delete()) throw new AssertionError("marker delete");
            capture(fixture, recognizer[0], manager, result, "disabled");
            Thread.sleep(500);
            check(!BluetoothMicRoute.isRouted(), "route held while disabled");
            result.putString("communication_after_disabled", typeOf(manager.getCommunicationDevice()));
        } finally {
            fixture.runOnMainSync(() -> recognizer[0].destroy());
            fixture.waitForIdleSync();
            if (wasEnabled) marker.createNewFile();
            else marker.delete();
        }
        return result;
    }

    private static void capture(Instrumentation fixture, SpeechRecognizer recognizer,
                                AudioManager manager, Bundle result, String label)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        int[] error = {0};
        long[] startMs = {0L};
        long[] readyMs = {0L};
        fixture.runOnMainSync(() -> {
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { ready.countDown(); }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onError(int code) { error[0] = code; ready.countDown(); }
                @Override public void onResults(Bundle results) { }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            startMs[0] = SystemClock.elapsedRealtime();
            recognizer.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM));
        });
        // The level callback needs real (non-silenced) audio; a locked phone
        // may withhold it, so routing evidence is sampled either way.
        ready.await(CAPTURE_SAMPLE_MS, TimeUnit.MILLISECONDS);
        readyMs[0] = SystemClock.elapsedRealtime() - startMs[0];
        String inputType = "none";
        List<AudioRecordingConfiguration> active = manager.getActiveRecordingConfigurations();
        for (AudioRecordingConfiguration configuration : active) {
            AudioDeviceInfo device = configuration.getAudioDevice();
            if (device != null) inputType = typeOf(device);
        }
        result.putInt(label + "_error", error[0]);
        result.putBoolean(label + "_routed", BluetoothMicRoute.isRouted());
        result.putString(label + "_input", inputType);
        result.putString(label + "_communication", typeOf(manager.getCommunicationDevice()));
        result.putLong(label + "_sample_ms", readyMs[0]);
        fixture.runOnMainSync(recognizer::cancel);
        fixture.waitForIdleSync();
    }

    private static void popup(Instrumentation fixture, AudioManager manager, Bundle result) {
        Instrumentation.ActivityMonitor monitor =
                fixture.addMonitor(RecognizeActivity.class.getName(), null, false);
        try {
            fixture.getTargetContext().startActivity(new Intent(fixture.getTargetContext(),
                    RecognizeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            android.app.Activity popup = monitor.waitForActivityWithTimeout(3_000L);
            boolean routed = false;
            String input = "none";
            long deadline = SystemClock.elapsedRealtime() + CAPTURE_SAMPLE_MS;
            while (SystemClock.elapsedRealtime() < deadline) {
                routed |= BluetoothMicRoute.isRouted();
                for (AudioRecordingConfiguration configuration
                        : manager.getActiveRecordingConfigurations()) {
                    AudioDeviceInfo device = configuration.getAudioDevice();
                    if (device != null) input = typeOf(device);
                }
                if (routed && !"none".equals(input)) break;
                SystemClock.sleep(50);
            }
            result.putBoolean("popup_launched", popup != null);
            result.putBoolean("popup_routed", routed);
            result.putString("popup_input", input);
            if (popup != null) {
                fixture.runOnMainSync(() -> {
                    android.view.View close = popup.findViewById(R.id.btn_close);
                    if (!popup.isFinishing() && close != null) close.performClick();
                });
                fixture.waitForIdleSync();
            }
        } finally {
            fixture.removeMonitor(monitor);
        }
    }

    private static String typeOf(AudioDeviceInfo device) {
        if (device == null) return "none";
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "builtin_mic";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "bluetooth_sco";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "bluetooth_a2dp";
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "ble_headset";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "earpiece";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "speaker";
            default: return "type_" + device.getType();
        }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    private BluetoothMicDeviceTest() {}
}
