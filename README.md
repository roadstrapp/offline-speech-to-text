# Offline Speech to Text (Android)

An offline, privacy-focused speech-to-text tool for Android, built with Rust. Tap the microphone on the keyboard you already use — your speech is transcribed entirely on-device and typed into any app. Also includes live subtitles and an optional dedicated voice keyboard.

## Features

- **Voice input in any app:** Tap the microphone on the keyboard you already use (SwiftKey, etc.) or a website's voice search, and your speech is transcribed straight into the text field. The app registers as your device's speech-to-text provider.
- **Offline & private transcription:** The Parakeet TDT model runs entirely on-device — no audio ever leaves your phone. The model is downloaded separately in your browser once, then the app needs no network access.
- **Live Subtitles:** Real-time captions for any audio/video playing on your device.
- **Optional voice keyboard:** A built-in keyboard you can switch to for voice input wherever you prefer it.
- **Default model languages:** The Parakeet TDT 0.6B v3 Q4 default supports 25 European languages, including English and Spanish.
- **Custom models:** Import any [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) GGUF model (Whisper, Nemotron streaming, Canary, more Parakeet variants, …) from a downloaded file — the app stays fully offline; downloads happen in your browser.
- **Efficient native backend:** All models run through [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) (ggml), wrapped in a safe Rust core.

## Screenshots

<p float="left">
  <img src=".screenshots/screenshot_home.png" width="30%" />
  <img src=".screenshots/screenshot_recording.png" width="30%" />
  <img src=".screenshots/screenshot_subtitles.png" width="30%" />
</p>

## Usage

### Voice input in any app (recommended)

1. Open **Offline Speech to Text** once and grant the microphone permission. The home screen shows a **Voice input** status — green when you're ready to go.
2. In any app, tap the **microphone** on your keyboard (e.g. Microsoft SwiftKey) or the voice-search mic on a website. A compact panel slides up over the app you're in, you speak, and your words are inserted as text. Tap to stop — or enable *Auto-stop after silence* in the app's settings to have it stop by itself.

The app plugs into Android's speech-to-text in **three** ways, so it works with a wide range of keyboards and apps:

| Path | Who uses it | What happens |
|---|---|---|
| **Voice-input popup** (`RECOGNIZE_SPEECH`) | SwiftKey, website voice search, many apps | The compact bottom panel opens over the current app |
| **System speech service** (`RecognitionService`) | Keyboards/apps using Android's `SpeechRecognizer` | Recognition runs invisibly in the background with automatic endpointing |
| **Voice keyboard (IME)** | Any keyboard, via the keyboard switcher — also HeliBoard/AnySoftKeyboard-style "switch to voice IME" mic keys | The dedicated voice keyboard opens |

Tap **Try voice input** on the home screen to test the whole flow in one tap.

**Keyboard notes** (mic-key behavior verified against each keyboard's source and tested in the emulator):

- **Microsoft SwiftKey** (not open source) opens the compact voice panel directly, like website voice search does. If SwiftKey's own voice typing opens instead, go to SwiftKey Settings → *Rich input* → turn off **Multi-modal voice typing**.
- **AnySoftKeyboard** is the open-source way to get the panel: its mic key fires the standard speech intent as long as no *voice keyboard* is enabled on the system. (If one is enabled — ours or Google's — it switches to that instead.)
- **HeliBoard, FlorisBoard, OpenBoard, Fossify Keyboard, Unexpected Keyboard:** their mic key never opens the panel — it switches to the system *voice input keyboard*. Enable the **Offline Speech to Text** keyboard (see below) and it opens automatically; its keyboard-switch key takes you back. **FUTO Keyboard** ships its own built-in voice input.
- **Gboard:** only uses Google's own voice typing, so it can't hand speech to this app at all.
- If Android shows a chooser, pick **Offline Speech to Text** and tap **Always**. If another app always opens, clear its default in *Settings → Apps*.

### Dedicated voice keyboard (optional)

Prefer voice input as its own keyboard? Enable the **Offline Speech to Text** keyboard via *Open Keyboard Settings* on the home screen, switch to it from your keyboard switcher, then tap **Tap to Record**. By default the recording keeps running even if you switch apps or the keyboard closes (turn off *Record in background* in settings if you don't want that) — the text is inserted when you come back.

### Live subtitles

Tap **Start Live Subtitles** and choose *Share entire screen* to get real-time, on-device captions for any audio or video playing on your device.

**Advanced: skip the screen-capture dialog.** Android shows a "Start recording or casting?" consent dialog every time subtitles start. You can pre-approve it once via adb — after that, subtitles start instantly and the setting survives reboots (USB debugging can be turned off again afterwards):

```bash
adb shell appops set --user 0 app.offlinespeechtotext PROJECT_MEDIA allow
```

To undo it:

```bash
adb shell appops set --user 0 app.offlinespeechtotext PROJECT_MEDIA default
```

This relies on the undocumented `PROJECT_MEDIA` app-op; on some OEM builds it may not work or may get reset by the system — the normal dialog remains the fallback. The same instructions are shown in-app under *Skip the permission dialog (advanced)*.

### Custom speech models

The recommended default is Parakeet TDT 0.6B v3 Q4. To keep the private APK small enough to deliver, the model is not embedded: open **Manage speech models**, use *Where to get models*, download it in your browser, then import it with the system file picker. The app verifies the exact 485,425,504 bytes and SHA-256 `b68557be1e3c40207fd7c4bd9d63f1d3316b963f15325bfb0cc16a8bb0ffd181` before activating it. This model supports 25 European languages, including English and Spanish. You may also import another compatible [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) GGUF model; every successful import becomes active immediately and reloads the engine. The app itself has no internet permission.

## Prerequisites

| Dependency | Installation |
|---|---|
| **JDK 17** | Android Studio (bundled) or `sudo pacman -S jdk17-openjdk` |
| **Android SDK** | Via Android Studio or `sdkmanager` |
| **Android NDK** | `sdkmanager "ndk;28.0.13004108"` |
| **Rust** | [rustup.rs](https://rustup.rs) + `rustup target add aarch64-linux-android` |
| **cargo-ndk** | `cargo install cargo-ndk` |

### Local Configuration

Create a `local.properties` file in the project root (this file is gitignored):

```properties
sdk.dir=/path/to/your/Android/Sdk
```

If your default Java is not JDK 17, uncomment and set `org.gradle.java.home` in `gradle.properties`:

```properties
org.gradle.java.home=/path/to/jdk17
# Examples:
#   /opt/android-studio/jbr          (Android Studio bundled JBR)
#   /usr/lib/jvm/java-17-openjdk     (System JDK 17)
```

## Building

### Debug APK
```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Release APK
```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

### Release AAB (Google Play)
```bash
./gradlew bundleRelease
# Output: app/build/outputs/bundle/release/app-release.aab
```

### Signing

For release builds, place a `release.keystore` in the project root and set these environment variables:

```bash
export KEY_ALIAS=release
export KEY_PASS=yourpassword
export STORE_PASS=yourpassword
```

### Model Assets

Private APK builds omit the model by default and offer the exact Parakeet TDT 0.6B v3 Q4 artifact through a pinned browser download. Its size and SHA-256 are verified during import. Developers can opt into a model-bearing build with `-PincludeBundledModel=true`; that build downloads and verifies the same artifact before packaging.

## Project Structure

```
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/dev/notune/transcribe/   # Android Java code
│       ├── res/                          # Resources (layouts, drawables, etc.)
│       ├── assets/                       # Model files (downloaded at build time)
│       └── jniLibs/                      # Native .so files (built by cargo-ndk)
├── src/                                  # Rust source code (cdylib)
├── Cargo.toml                            # Rust crate manifest
├── build.gradle.kts                      # Root Gradle config
├── app/build.gradle.kts                  # App module config (AGP 8.7.3)
├── settings.gradle.kts
├── gradle.properties
└── fastlane/metadata/android/            # F-Droid metadata
```

## Acknowledgments

- **Speech Model:** [Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) by NVIDIA and Suno.
    - GGUF conversion by [handy-computer](https://huggingface.co/handy-computer/parakeet-tdt-0.6b-v3-gguf).
    - Licensed under [CC-BY 4.0](https://creativecommons.org/licenses/by/4.0/).
- **Inference Backend:** [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) by CJ Pais / Handy Computer.

## License

[MIT](LICENSE)
