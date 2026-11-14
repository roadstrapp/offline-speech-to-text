package dev.notune.transcribe;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * A short falling two-tone cue: the Bluetooth microphone stopped and the
 * recording continues on the phone microphone. Played as assistance
 * sonification, which reaches hearing aids and headsets whether or not they
 * are still in call mode, and short enough not to disturb the recording.
 */
final class CaptureCue {
    private static final int RATE = 16_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CaptureCue() {}

    static void playFallback() {
        try {
            short[] pcm = tones(new int[] {880, 440}, 160, 70, 0.35);
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length * 2)
                    .build();
            track.write(pcm, 0, pcm.length);
            track.play();
            long durationMs = pcm.length * 1000L / RATE;
            MAIN.postDelayed(() -> {
                try {
                    track.stop();
                } catch (IllegalStateException ignored) { }
                track.release();
            }, durationMs + 300);
        } catch (RuntimeException e) {
            Log.w(BluetoothMicRoute.TAG, "fallback cue unavailable", e);
        }
    }

    /** Tones of {@code toneMs} each, {@code gapMs} apart, with short fades against clicks. */
    static short[] tones(int[] frequencies, int toneMs, int gapMs, double volume) {
        int tone = RATE * toneMs / 1000;
        int gap = RATE * gapMs / 1000;
        int fade = RATE * 8 / 1000;
        short[] out = new short[frequencies.length * tone + (frequencies.length - 1) * gap];
        int at = 0;
        for (int index = 0; index < frequencies.length; index++) {
            for (int i = 0; i < tone; i++) {
                double envelope = Math.min(1.0, Math.min(i, tone - 1 - i) / (double) fade);
                double sample = Math.sin(2 * Math.PI * frequencies[index] * i / RATE);
                out[at + i] = (short) Math.round(sample * envelope * volume * Short.MAX_VALUE);
            }
            at += tone + gap;
        }
        return out;
    }
}
