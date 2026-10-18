# offlinestt — embeddable offline speech-to-text engine

A small Android library: **one complete 16 kHz mono utterance in, text out**, running entirely on
the device. It is the inference core of this repository's app, extracted so other apps can embed it
without inheriting an Activity, a service, a model picker or a microphone.

It is a thin facade over [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp)
(ggml, GGUF models) with a JNI surface and a Kotlin API. There is no network access, no telemetry
and no Google component in it.

## What the engine does — and does not

| The engine | The host app |
| --- | --- |
| Loads a GGUF model, decodes one utterance, returns text | Records audio, finds where the utterance ends |
| Picks the CPU build that is safe on this phone (probe) | Downloads and verifies the model file |
| Picks an energy-conscious thread count | Checks free RAM before loading, closes the engine when idle |
| Degrades a language hint the model rejects (`de-DE` → `de` → none) | Asks for the microphone permission |
| Contains a panic or a hostile GGUF instead of killing the process | Decides what to do with the text |

## Kotlin API

```kotlin
import app.roadstr.offlinestt.OfflineSttEngine
import app.roadstr.offlinestt.OfflineSttException

if (!OfflineSttEngine.isSupported()) return            // not arm64, or the libraries do not load

// Off the main thread: loading takes about a second and holds ~1.1 GB of RAM for Parakeet.
OfflineSttEngine.load(modelFile, language = "it").use { engine ->
    val result = engine.transcribe(pcm)                 // FloatArray, 16 kHz, mono, -1.0..1.0
    println("${result.text} (${result.decodeMillis} ms)")
}
```

* Audio must be between 100 ms and 30 s; anything else is `OfflineSttException.InvalidAudio`.
* `cancel()` may be called from any thread and makes the running `transcribe` throw
  `OfflineSttException.Cancelled`.
* A second `transcribe` while one runs throws `Busy` instead of queueing behind the UI.
* `close()` is idempotent and frees the model once a call in flight returns. A stale handle is
  `Closed`, never a use-after-free: engines are addressed by opaque ids kept in a registry.
* Failures cross JNI as plain JDK exceptions only, so R8/ProGuard cannot break error reporting.
  The classes in `app.roadstr.offlinestt` need no keep rules beyond the usual
  `-keepclasseswithmembernames class * { native <methods>; }`.

The engine appends 500 ms of silence to every utterance before decoding. Offline decoders need a
little right context to emit the final tokens; this is inference-only silence, the microphone is
never involved.

## Building the native libraries

```sh
ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.1.12297006 ./scripts/build-android.sh
```

Needs Rust with the `aarch64-linux-android` target, `cargo-ndk` and CMake. Output, in
`dist/arm64-v8a/`:

| File | Purpose |
| --- | --- |
| `libofflinestt_armv8.so` | Baseline Armv8-A. Runs on every arm64 phone. |
| `libofflinestt_dotprod.so` | Armv8.2-A + dot-product + fp16. Much faster where the CPU has them. |
| `libofflinestt_cpu_probe.so` | Reads `AT_HWCAP` and tells the loader which of the two is safe. |
| `libc++_shared.so` | The C++ runtime both builds link against. |
| `SHA256SUMS` | Checksums of the above. |

Two inference builds exist because the fast one traps with `SIGILL` on CPUs without the extensions.
The loader fails closed: if the probe cannot be loaded it uses the baseline build.

* **Reproducible.** The same source builds to byte-identical libraries from a different directory
  (checked by building from a copy at another path and comparing `SHA256SUMS`). Host apps that
  commit the binaries should keep a test or CI step that rebuilds and compares.
* **Verified instruction sets.** The baseline library contains no `sdot`/fp16 instructions; the
  dot-product library does.
* **16 KB pages.** Every library is linked with `max-page-size=16384`.
* **minSdk 24**, arm64-v8a only. On other ABIs `isSupported()` is false and the host should fall
  back to something else.

## Models

No model ships in this repository. Any GGUF that transcribe.cpp 0.2.3 can load works. The reference
configuration is Parakeet TDT 0.6B v3 (Q4_K_M), 25 European languages including Italian, German,
French, Spanish, Portuguese and English:

| | |
| --- | --- |
| File | `parakeet-tdt-0.6b-v3-Q4_K_M.gguf` |
| Size | 485 425 504 bytes |
| SHA-256 | `b68557be1e3c40207fd7c4bd9d63f1d3316b963f15325bfb0cc16a8bb0ffd181` |
| URL | `https://huggingface.co/handy-computer/parakeet-tdt-0.6b-v3-gguf/resolve/85ac09ea12fc4b1112fa76810059364bc6adc9de/parakeet-tdt-0.6b-v3-Q4_K_M.gguf` |
| License | **CC-BY-4.0** — attribution to NVIDIA is required; show it where the host lists its licenses |

Hosts must treat a downloaded model as untrusted until its size and SHA-256 match a value pinned in
the host's own source. Pin the exact commit in the URL, as above, so the file cannot change under you.

## Tests and measurements

```sh
cargo test                                   # input validation, threads, loader refusals (host)
cargo run --release --example transcribe_wav -- model.gguf audio.wav it
```

The example runs the real engine and a real model on the host. On a 32-thread x86-64 desktop,
Parakeet v3 Q4 loads in ~0.4 s and decodes ~3 s of Italian speech in ~0.35 s. **This is not phone
speed**; on-device timing has to be measured on the phone.

## Layout

```
engine/
  src/core.rs        engine: load, validate, decode, language-hint fallback (no JNI)
  src/threads.rs     thread-count heuristic
  src/jni_api.rs     JNI surface, handle registry, panic containment (Android only)
  cpp/cpu_probe.c    getauxval() CPU feature probe
  kotlin/            the Kotlin API and loader to vendor into an app
  scripts/           reproducible Android build
  examples/          host-side runner for a real model
```

## License

MIT, like the rest of this repository. transcribe.cpp and ggml are MIT. Models carry their own
licenses (see above).
