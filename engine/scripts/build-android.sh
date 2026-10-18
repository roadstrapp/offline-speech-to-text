#!/usr/bin/env bash
# Builds the Android arm64-v8a native libraries of the engine, reproducibly.
#
#   ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.1.12297006 ./scripts/build-android.sh [out-dir]
#
# Output (default: ./dist/arm64-v8a):
#   libofflinestt_armv8.so    baseline Armv8-A build, runs on every arm64 phone
#   libofflinestt_dotprod.so  Armv8.2-A + dot-product + fp16 build, ~2x faster where supported
#   libofflinestt_cpu_probe.so  picks between the two at runtime (getauxval)
#   libc++_shared.so          the C++ runtime both builds link against
#   SHA256SUMS
#
# Two inference builds are shipped because the fast one traps with SIGILL on CPUs
# without the extensions; the Kotlin loader asks the probe first.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$here/dist/arm64-v8a}"
platform="${ANDROID_PLATFORM:-24}"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK directory}"
# CMake's Android detection (used by the ggml build) looks for each of these names.
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME" ANDROID_NDK="$ANDROID_NDK_HOME"
command -v cargo-ndk >/dev/null || { echo "cargo-ndk is required (cargo install cargo-ndk)" >&2; exit 1; }

# Fixed so the toolchain embeds no timestamps; the build must not depend on where it runs.
export SOURCE_DATE_EPOCH=1
build="$here/build"
mkdir -p "$out" "$build"

build_variant() {
    local name="$1" arch="$2"
    local flags=(
        "-C" "link-arg=-Wl,-soname,libofflinestt_${name}.so"
        "-C" "link-arg=-Wl,-z,max-page-size=16384"
        "-C" "link-arg=-Wl,--build-id=none"
        "--remap-path-prefix=$here=/engine"
        "--remap-path-prefix=${CARGO_HOME:-$HOME/.cargo}=/cargo"
    )
    (
        cd "$here"
        export CARGO_TARGET_DIR="$build/rust-$name"
        export TRANSCRIBE_CMAKE_ARGS="-DGGML_CPU_ARM_ARCH=$arch"
        export RUSTFLAGS="${flags[*]}"
        cargo ndk -t arm64-v8a --platform "$platform" -o "$build/jni-$name" build --release --locked
    )
    cp "$build/jni-$name/arm64-v8a/libofflinestt.so" "$out/libofflinestt_${name}.so"
}

build_variant armv8 armv8-a
build_variant dotprod armv8.2-a+dotprod+fp16

clang="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
"$clang" --target="aarch64-linux-android$platform" -shared -fPIC -Oz \
    -Wl,-soname,libofflinestt_cpu_probe.so -Wl,-z,max-page-size=16384 -Wl,--build-id=none \
    "$here/cpp/cpu_probe.c" -o "$out/libofflinestt_cpu_probe.so"
cp "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$out/"

(cd "$out" && sha256sum lib*.so > SHA256SUMS && cat SHA256SUMS)
