package dev.notune.transcribe;

import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Loads the optimized native backend when supported, otherwise baseline Armv8-A. */
final class NativeLibraries {
    private static final String TAG = "OfflineVoiceInput";
    private static final long AT_HWCAP = 16;
    private static final long HWCAP_ASIMDHP = 1L << 10;
    private static final long HWCAP_ASIMDDP = 1L << 20;
    private static boolean loaded;

    private NativeLibraries() {}

    static synchronized void load() {
        if (loaded) return;
        try {
            System.loadLibrary("c++_shared");
        } catch (UnsatisfiedLinkError error) {
            Log.w(TAG, "libc++_shared was not preloaded; loading app backend directly", error);
        }
        String backend = supportsOptimizedBackend()
                ? "android_transcribe_app_dotprod"
                : "android_transcribe_app_armv8";
        Log.i(TAG, "Loading native backend: " + backend);
        System.loadLibrary(backend);
        loaded = true;
    }

    static boolean supportsOptimizedBackend() {
        try {
            byte[] auxv = Files.readAllBytes(Paths.get("/proc/self/auxv"));
            ByteBuffer entries = ByteBuffer.wrap(auxv).order(ByteOrder.nativeOrder());
            while (entries.remaining() >= 16) {
                long type = entries.getLong();
                long value = entries.getLong();
                if (type == 0) break;
                if (type == AT_HWCAP) {
                    return (value & HWCAP_ASIMDHP) != 0
                            && (value & HWCAP_ASIMDDP) != 0;
                }
            }
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Cannot read CPU capabilities; using baseline Armv8-A", error);
        }
        return false;
    }
}
