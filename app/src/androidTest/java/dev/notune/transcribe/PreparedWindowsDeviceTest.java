package dev.notune.transcribe;

import android.app.Activity;
import android.app.Instrumentation;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Same public PCM on both production decoding paths. Never exports hypotheses. */
final class PreparedWindowsDeviceTest {
    static long[] run(Instrumentation fixture, Activity activity) throws Exception {
        byte[] wav;
        try (InputStream input = fixture.getTargetContext().getAssets().open("bench.wav")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] block = new byte[8192];
            int count;
            while ((count = input.read(block)) != -1) bytes.write(block, 0, count);
            wav = bytes.toByteArray();
        }
        int data = -1;
        for (int offset = 12; offset + 8 <= wav.length;) {
            int size = (wav[offset + 4] & 255) | ((wav[offset + 5] & 255) << 8)
                    | ((wav[offset + 6] & 255) << 16) | ((wav[offset + 7] & 255) << 24);
            if (size < 0 || size > wav.length - offset - 8) throw new AssertionError("invalid public WAV");
            if (wav[offset] == 'd' && wav[offset + 1] == 'a' && wav[offset + 2] == 't' && wav[offset + 3] == 'a') {
                if (size != 352000) throw new AssertionError("unexpected public fixture");
                data = offset + 8; break;
            }
            offset += 8 + size + (size & 1);
        }
        if (data < 0) throw new AssertionError("missing public PCM");
        // Six 11-second copies plus a five-second hesitation inside the first
        // copy. Forces an existing long window; silence alone creates none.
        float[] audio = new float[71 * 16000];
        int position = 0;
        for (int repeat = 0; repeat < 6; repeat++) {
            for (int sample = 0; sample < 176000; sample++) {
                if (repeat == 0 && sample == 5 * 16000) position += 5 * 16000;
                int offset = data + sample * 2;
                audio[position++] = (short) ((wav[offset] & 255) | (wav[offset + 1] << 8)) / 32768f;
            }
        }
        java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod("verifyPreparedWindowsNative", float[].class);
        method.setAccessible(true);
        long[] evidence = (long[]) method.invoke(activity, (Object) audio);
        if (evidence == null || evidence.length != 5 || evidence[0] != 1 || evidence[1] < 1)
            throw new AssertionError("prepared/deferred production decode differed or bounded model unavailable");
        return evidence;
    }
}
