#include <jni.h>
#include <sys/auxv.h>
#include <asm/hwcap.h>

/*
 * Tiny baseline-only probe loaded before either inference backend. Android's
 * libc exposes getauxval from API 18, while hardened devices may deny direct
 * reads of /proc/self/auxv from an app sandbox.
 */
JNIEXPORT jboolean JNICALL
Java_dev_notune_transcribe_NativeLibraries_supportsOptimizedBackendNative(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    const unsigned long capabilities = getauxval(AT_HWCAP);
    const unsigned long required = HWCAP_ASIMDHP | HWCAP_ASIMDDP;
    return (capabilities & required) == required ? JNI_TRUE : JNI_FALSE;
}
