package dev.notune.transcribe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserManager;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded capture capability observations; never stores audio or changes privacy policy. */
final class AudioCaptureCapabilityDeviceTest {
    static void run(Instrumentation fixture) {
        Bundle result = new Bundle();
        Activity activity = null;
        int code = Activity.RESULT_OK;
        try {
            android.content.Context context = fixture.getTargetContext();
            if (!"app.offlinespeechtotext.validation".equals(context.getPackageName())) throw new AssertionError("isolated fixture only");
            activity = fixture.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AudioManager manager = context.getSystemService(AudioManager.class);
            JSONObject report = new JSONObject();
            report.put("record_permission", context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
            report.put("microphone_muted", manager.isMicrophoneMute());
            report.put("no_record_audio_restriction", context.getSystemService(UserManager.class).hasUserRestriction("no_record_audio"));
            JSONArray inputs = new JSONArray();
            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                JSONObject input = new JSONObject(); input.put("type", device.getType());
                input.put("rates", new JSONArray(device.getSampleRates()));
                input.put("encodings", new JSONArray(device.getEncodings())); inputs.put(input);
            }
            report.put("inputs", inputs);
            JSONArray cases = new JSONArray();
            for (int source : new int[] {MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION}) {
                for (int rate : new int[] {16000, 48000}) {
                    JSONObject attempt = new JSONObject(); attempt.put("source", source); attempt.put("requested_rate", rate);
                    AudioRecord record = null;
                    try {
                        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                        attempt.put("minimum_buffer", min);
                        record = new AudioRecord.Builder().setAudioSource(source).setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                                .setBufferSizeInBytes(Math.max(min * 4, rate)).build();
                        attempt.put("initialized", record.getState() == AudioRecord.STATE_INITIALIZED);
                        record.startRecording();
                        attempt.put("recording", record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING);
                        short[] scratch = new short[1024]; int samples = 0; int lastRead = 0;
                        long deadline = SystemClock.elapsedRealtime() + 1000;
                        while (SystemClock.elapsedRealtime() < deadline) {
                            lastRead = record.read(scratch, 0, scratch.length, AudioRecord.READ_NON_BLOCKING);
                            if (lastRead < 0) break;
                            samples += lastRead; SystemClock.sleep(10);
                        }
                        attempt.put("samples", samples); attempt.put("last_read", lastRead);
                        AudioDeviceInfo actual = record.getRoutedDevice(); attempt.put("actual_type", actual == null ? -1 : actual.getType());
                    } catch (Exception error) { attempt.put("error", error.getClass().getSimpleName()); }
                    finally { if (record != null) { try { record.stop(); } catch (Exception ignored) { } record.release(); } }
                    cases.put(attempt);
                }
            }
            report.put("cases", cases); result.putString("capture_observations", report.toString());
            result.putBoolean("capture_capability", true);
        } catch (Throwable error) { code = Activity.RESULT_CANCELED; result.putString("failure", error.toString()); }
        finally { if (activity != null) { Activity owned = activity; fixture.runOnMainSync(owned::finish); } }
        fixture.finish(code, result);
    }
}
