package dev.notune.transcribe;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

/**
 * Bluetooth microphone capture through {@link AudioRecord}: 16 kHz mono PCM16
 * with the VOICE_COMMUNICATION source and the headset's input as preferred
 * device, opened after the communication device is routed and settled.
 *
 * <p>This is the configuration that records LE Audio hearing aids reliably.
 * An AAudio stream on the same input (float, voice-communication preset,
 * explicit device) ran at full rate but delivered only digital zeros on
 * those aids, also after a reopen. Samples are converted to float and handed
 * to native code ({@code bt_audio_record.rs}), which feeds them to the same
 * capture callbacks as every other stream. Created and driven from native
 * code only.
 */
final class BluetoothAudioRecord {
    private static final int RATE = 16_000;
    /** 20 ms reads: low latency for the level meter, few JNI crossings. */
    private static final int READ_FRAMES = RATE / 50;
    /**
     * Silence played to the headset while its input comes up. On Pixel's audio
     * HAL an LE Audio input opened with no LE output active starts without the
     * "BLE restart" step ("skip kBleRestart due to no ble edge connected") and
     * then delivers only digital zeros for the whole recording, while the
     * Bluetooth stack reports both directions streaming. An active output
     * during the setup avoids it.
     */
    private static final int PRIME_MS = 3_000;

    private final AudioRecord record;
    private final long handle;
    private final int preferredDeviceId;
    private volatile boolean running;
    private Thread thread;

    private BluetoothAudioRecord(AudioRecord record, long handle, int preferredDeviceId) {
        this.record = record;
        this.handle = handle;
        this.preferredDeviceId = preferredDeviceId;
    }

    /** Null when the recorder cannot be created on this device. */
    @SuppressLint("MissingPermission")
    static BluetoothAudioRecord open(AudioManager manager, int inputDeviceId, long handle) {
        AudioRecord record = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(Math.max(min * 4, RATE))
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                Log.w(BluetoothMicRoute.TAG, "AudioRecord: not initialized");
                return null;
            }
            AudioDeviceInfo input = null;
            if (manager != null && inputDeviceId > 0) {
                for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    if (device.getId() == inputDeviceId) input = device;
                }
            }
            if (input != null) record.setPreferredDevice(input);
            return new BluetoothAudioRecord(record, handle, input == null ? 0 : input.getId());
        } catch (RuntimeException e) {
            if (record != null) { try { record.release(); } catch (RuntimeException ignored) { } }
            Log.w(BluetoothMicRoute.TAG, "AudioRecord: could not be created", e);
            return null;
        }
    }

    /** Starts recording and the reader thread; false if Android refused. */
    synchronized boolean start() {
        if (running) return true;
        if (thread != null && thread.isAlive()) return false;
        if (primer != null && primer.isAlive()) return false;
        try {
            record.startRecording();
        } catch (RuntimeException e) {
            Log.w(BluetoothMicRoute.TAG, "AudioRecord: start refused", e);
            return false;
        }
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            Log.w(BluetoothMicRoute.TAG, "AudioRecord: not recording after start");
            return false;
        }
        running = true;
        thread = new Thread(this::pump, "ostt-bt-record");
        thread.start();
        primer = new Thread(this::prime, "ostt-bt-prime");
        primer.start();
        return true;
    }

    private Thread primer;
    private volatile AudioTrack primerTrack;

    /** Plays {@link #PRIME_MS} of silence on the communication route, unless stopped first. */
    private void prime() {
        AudioTrack track = null;
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(min, RATE / 5))
                    .build();
            primerTrack = track;
            if (!running) return;
            track.play();
            short[] silence = new short[RATE / 50];
            long until = System.currentTimeMillis() + PRIME_MS;
            while (running && System.currentTimeMillis() < until) {
                // blocking write: paces the loop at real time
                if (track.write(silence, 0, silence.length) < 0) break;
            }
            Log.i(BluetoothMicRoute.TAG, "primer: played silence to the headset during input setup");
        } catch (RuntimeException e) {
            Log.w(BluetoothMicRoute.TAG, "primer: unavailable", e);
        } finally {
            if (primerTrack == track) primerTrack = null;
            if (track != null) {
                try {
                    track.stop();
                } catch (IllegalStateException ignored) { }
                track.release();
            }
        }
    }

    synchronized void stop() {
        running = false;
        AudioTrack primeOutput = primerTrack;
        if (primeOutput != null) {
            try { primeOutput.stop(); } catch (RuntimeException ignored) { }
        }
        try {
            record.stop();
        } catch (RuntimeException ignored) { }
        Thread reader = thread;

        if (reader != null && reader != Thread.currentThread()) {
            try {
                reader.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (reader == null || !reader.isAlive()) thread = null;
        Thread prime = primer;
        if (prime != null && prime != Thread.currentThread()) {
            try { prime.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (prime == null || !prime.isAlive()) primer = null;
    }

    /** Stops and frees the recorder; native callback admission is retired separately. */
    void release() {
        stop();
        try {
            record.release();
        } catch (RuntimeException ignored) { }
    }

    /**
     * How long to wait for Android to report the routed input after start. An
     * LE Audio input (hearing aids, LE headsets) is set up only when recording
     * starts: the stack reconfigures the link to the conversational context
     * and builds the isochronous data path, and getRoutedDevice() stays null
     * until the audio HAL has started the stream. On a Pixel with LE Audio
     * hearing aids that took about 1 s routinely and up to 3.5 s; with a 500 ms
     * wait every such start was classified "not on Bluetooth" and fell back to
     * the phone microphone although the stream was about to deliver the aids'
     * audio. Returns as soon as the device is known (SCO: tens of ms).
     */
    private static final long ROUTED_DEVICE_WAIT_MS = 5_000L;

    /** The routed input; right after start Android may not report it yet. */
    int routedDeviceId() {
        long deadline = android.os.SystemClock.elapsedRealtime() + ROUTED_DEVICE_WAIT_MS;
        do {
            AudioDeviceInfo routed = record.getRoutedDevice();
            if (routed != null) return routed.getId();
            if (!running) return 0;
            android.os.SystemClock.sleep(10L);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        return 0; // Requested input is not evidence of actual routing.
    }

    int sessionId() {
        return record.getAudioSessionId();
    }

    private void pump() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] pcm = new short[READ_FRAMES];
        float[] samples = new float[READ_FRAMES];
        while (running) {
            int read = record.read(pcm, 0, pcm.length);
            if (!running) break;
            if (read > 0) {
                for (int i = 0; i < read; i++) samples[i] = pcm[i] / 32768f;
                nativeFeed(handle, samples, read);
            } else if (read < 0) {
                running = false;
                nativeError(handle, read);
                return;
            }
        }
    }

    private static native void nativeFeed(long handle, float[] samples, int count);
    private static native void nativeError(long handle, int code);
}
