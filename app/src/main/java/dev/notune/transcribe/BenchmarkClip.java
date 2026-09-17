package dev.notune.transcribe;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reads the fixed 16 kHz mono PCM benchmark asset shared by benchmark surfaces. */
final class BenchmarkClip {
    private BenchmarkClip() {}

    static float[] read(Context context) throws IOException {
        byte[] bytes;
        try (InputStream in = context.getAssets().open("bench.wav");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            bytes = out.toByteArray();
        }
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            int chunkSize = (bytes[offset + 4] & 0xff) | ((bytes[offset + 5] & 0xff) << 8)
                    | ((bytes[offset + 6] & 0xff) << 16) | ((bytes[offset + 7] & 0xff) << 24);
            if (bytes[offset] == 'd' && bytes[offset + 1] == 'a'
                    && bytes[offset + 2] == 't' && bytes[offset + 3] == 'a') {
                int start = offset + 8;
                int count = Math.min(chunkSize, bytes.length - start) / 2;
                float[] samples = new float[count];
                for (int index = 0; index < count; index++) {
                    int low = bytes[start + 2 * index] & 0xff;
                    int high = bytes[start + 2 * index + 1];
                    samples[index] = ((high << 8) | low) / 32768.0f;
                }
                return samples;
            }
            offset += 8 + chunkSize + (chunkSize & 1);
        }
        throw new IOException("benchmark WAV has no data chunk");
    }
}
