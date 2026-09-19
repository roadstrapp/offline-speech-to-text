package dev.notune.transcribe;

import android.util.Log;

/** Loads the optimized native backend when supported, otherwise baseline Armv8-A. */
final class NativeLibraries {
    private static final String TAG = "OfflineVoiceInput";
    private static boolean loaded;
    private static boolean probeLoadAttempted;
    private static boolean probeLoaded;
    private static Boolean optimizedBackend;

    private NativeLibraries() {}

    static synchronized void load() {
        if (loaded) return;
        try {
            System.loadLibrary("c++_shared");
        } catch (UnsatisfiedLinkError error) {
            Log.w(TAG, "libc++_shared was not preloaded; loading app backend directly", error);
        }
        loadProbe();
        String backend = supportsOptimizedBackend()
                ? "android_transcribe_app_dotprod"
                : "android_transcribe_app_armv8";
        Log.i(TAG, "Loading native backend: " + backend);
        System.loadLibrary(backend);
        loaded = true;
    }

    static synchronized boolean supportsOptimizedBackend() {
        if (optimizedBackend != null) return optimizedBackend;
        loadProbe();
        if (!probeLoaded) {
            optimizedBackend = false;
            return false;
        }
        try {
            optimizedBackend = supportsOptimizedBackendNative();
        } catch (UnsatisfiedLinkError error) {
            optimizedBackend = false;
        }
        return optimizedBackend;
    }

    private static void loadProbe() {
        if (probeLoadAttempted) return;
        probeLoadAttempted = true;
        try {
            System.loadLibrary("ostt_cpu_probe");
            probeLoaded = true;
        } catch (UnsatisfiedLinkError error) {
            // Fail closed. The baseline backend works on every supported ABI,
            // while guessing dot-product support could crash the process.
            Log.w(TAG, "CPU feature probe unavailable; using baseline Armv8-A");
        }
    }

    /** Uses Android libc getauxval(AT_HWCAP); unlike /proc, this works in app sandboxes. */
    private static native boolean supportsOptimizedBackendNative();
}
