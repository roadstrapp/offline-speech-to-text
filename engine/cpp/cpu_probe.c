#include <jni.h>
#include <sys/auxv.h>
#include <asm/hwcap.h>

/*
 * Tiny baseline-only probe, loaded before either inference build. Android's libc
 * exposes getauxval since API 18, while hardened devices may deny an app direct
 * reads of /proc/self/auxv. The dot-product build is only safe when the CPU
 * reports both half-precision (fp16) and dot-product (dotprod) vector support.
 */
JNIEXPORT jboolean JNICALL
Java_app_roadstr_offlinestt_NativeLibraries_supportsDotprodNative(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    const unsigned long capabilities = getauxval(AT_HWCAP);
    const unsigned long required = HWCAP_ASIMDHP | HWCAP_ASIMDDP;
    return (capabilities & required) == required ? JNI_TRUE : JNI_FALSE;
}
