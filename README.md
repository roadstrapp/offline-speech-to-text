# Offline Speech to Text for Android

Private, on-device speech recognition for Android apps, keyboards, shared audio, and live captions. Install the current public build from [Zapstore](https://zapstore.dev/apps/app.offlinespeechtotext); source and development history live in the project-key-owned [Nostr repository](https://gitworkshop.dev/npub1jg2qmt3rxjksq65v3nk7dq6yaz2c4uf3qt9epdhxwms2xwd47j2s38a228/relay.ngit.dev/offline-speech-to-text).

## Features

- Dictate through Android's standard voice-input panel or `SpeechRecognizer` service; optionally move the panel to either side for one-handed use.
- Use the optional voice keyboard in ordinary and privacy/no-learning text fields. Password fields remain blocked.
- Reach pause/resume, cancel, insert-and-stay, and insert-and-switch in a compact 2×2 recording grid; use the rightmost globe to switch keyboards while idle.
- Hold and drag Backspace to highlight from the first character, move left or right one character at a time, and release to delete the selected range.
- Caption audio playing on the device with Live Subtitles.
- Open or share an audio file to transcribe it.
- Start immediately with the included Parakeet TDT 0.6B v3 Q4 model for 25 European languages—no connection or download required—or browse 52 exact optional downloads across every model family supported by the pinned runtime, including Whisper and 1.1B Parakeet variants.
- Choose a goal—balanced, compact, multilingual, translation, or higher accuracy—and get a compatible recommendation based on language, memory, storage, and model size. Every catalog card shows a practical size/fit estimate.
- Verify downloaded model size and SHA-256 before atomic activation; interrupted downloads resume safely and never replace the active model.
- Choose automatic or manual stopping, pause other audio while listening, keep an IME recording active across app switches, select inserted text, and tune inference threads.
- Benchmark the active model on the built-in recording. Multi-model comparison was evaluated but is not offered because loading a batch safely would block live dictation surfaces.
- Keep newer Arm64 phones on optimized dot-product/FP16 inference while safely falling back to baseline Armv8-A on older devices.
- Use light, dark, or system appearance with an accessible amber interface.
- Enable a bounded, content-free diagnostic log when troubleshooting. It is off by default and can be cleared in the app.

## Screenshots

<p float="left"><img src=".screenshots/screenshot_home.png" alt="Compact home and setup screen" width="30%" /> <img src=".screenshots/screenshot_models.png" alt="Guided offline model selection" width="30%" /> <img src=".screenshots/screenshot_recording.png" alt="Active voice keyboard controls" width="30%" /></p>

## Set up

1. Grant microphone access, then use **Try voice input**. The included default model works entirely offline from first launch.
2. Tap a microphone in an app or keyboard that launches Android voice recognition. If Android asks, choose **Offline Speech to Text**.
3. Optional: open **Manage speech models**. Installed-model selection is first, followed by the device recommendation and complete download catalog. Download progress stays in the card you tapped. Every Download button verifies exact size and SHA-256 before installing and selecting a model. **Browse model websites** and manual GGUF import remain separate fallbacks below the download choices.

Optional paths:

- **Voice keyboard:** Open **Keyboard settings**, enable Offline Speech to Text, then choose it from Android's keyboard switcher. HeliBoard, FlorisBoard, OpenBoard, Fossify Keyboard, and Unexpected Keyboard use the configured voice IME for their microphone action. The idle keyboard-switch button is at the right edge.
- **Long recording:** Open **Long recording**, confirm with **Start recording**, then use the private notification to stop or discard even after leaving the app. Review and explicitly share the result; the app never inserts it into a different editor.
- **Live Subtitles:** Tap **Start Live Subtitles**, approve screen-audio capture, and play the audio you want captioned.
- **Audio files:** Share or open an audio file with Offline Speech to Text.

## Compatibility and fallback

Android voice-input integrations do not all use the same surface:

| Surface | Experience | Best for |
|---|---|---|
| `ACTION_RECOGNIZE_SPEECH` | Offline Speech to Text owns the recording panel and returns a final result. | Apps and keyboards that launch Android's standard voice UI. |
| `SpeechRecognizer` + `RecognitionService` | The calling app owns the UI; the recognition provider normally owns microphone capture. | Apps with a custom dictation interface when Android permits the selected provider to record. |
| `SpeechRecognizer` + `EXTRA_AUDIO_SOURCE` | The calling app owns both the UI and microphone capture and streams PCM audio to the provider. | Custom app UI on Android 13+ when a provider supports caller-supplied audio. |
| Voice IME | Offline Speech to Text is the active keyboard. | Keyboards such as HeliBoard whose microphone action switches to the configured voice IME. |

The app or keyboard decides which surface it exposes. Gboard and FUTO Keyboard use their own voice systems. If an app-owned custom flow cannot safely use the recognition service, it should fall back to `ACTION_RECOGNIZE_SPEECH` before opening the microphone.

See [Android integration](docs/android-integration.md) for provider resolution, permissions, callbacks, caller-audio streaming, lifecycle cleanup, origin validation, fallback, and a scoped Kotlin example.

## Integrate a custom dictation UI

For an app-owned interface like White Noise's composer, keep microphone capture and UI state in your app and use Offline Speech to Text only as the recognition provider:

1. Resolve this app's `RecognitionService` and create `SpeechRecognizer` with that explicit component. Do not change the user's global recognizer setting.
2. On Android 13+, stream 16 kHz, mono, signed 16-bit little-endian PCM through `RecognizerIntent.EXTRA_AUDIO_SOURCE`. Close the writer to finish input; use `cancel()` for discard.
3. Treat callbacks as a generation-scoped state machine. Ignore late callbacks after cancel, error, editor replacement, account/conversation change, or a switch to a sensitive field.
4. Call `destroy()` on every terminal path. If explicit provider selection or caller-supplied audio is unavailable, fall back to `ACTION_RECOGNIZE_SPEECH` before opening the microphone.

The complete callback ordering, format extras, API checks, and Kotlin reference implementation are in [Android integration](docs/android-integration.md#custom-ui-with-caller-supplied-audio).

## Privacy

Recognition and inference run on the device, and the included default model needs no network access or network-permission prompt. The app has Android's install-time `INTERNET` capability only for optional model downloads you explicitly start or source pages you explicitly open; `ACCESS_NETWORK_STATE` only detects an unavailable connection so the app can explain a failed download. The app makes no network request on launch or onboarding. If access is unavailable when you tap Download, the app explains the problem and opens Android's app settings. After a successful download, it offers the same settings so systems with a revocable per-app Network toggle can remove access again. Standard Android does not expose a runtime request/revoke API for the normal `INTERNET` permission. The catalog is bundled metadata, so viewing and filtering it is offline. Downloads are verified before private installation, and manual model import works offline. The app keeps no transcription history. Results exist only where you insert or explicitly share them.

Diagnostic logging is disabled by default. When enabled, it records bounded lifecycle, model, timing, and error metadata—not microphone audio, utterances, transcripts, partial hypotheses, prompts, dictionary content, editor text, or transcript-derived filenames.

Live Subtitles uses Android's MediaProjection consent flow to capture playback audio. Android shows its normal microphone or screen-capture privacy indicators while capture is active.

## Build and contribute

Requirements: JDK 17, Android SDK 35, Android NDK 28.0.13004108, Rust with the `aarch64-linux-android` target, and `cargo-ndk`.

Create `local.properties` in the repository root:

```properties
sdk.dir=/path/to/Android/Sdk
```

Build and test a debug APK:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Native libraries are built automatically through `cargo-ndk`. Normal APK builds download, verify, and package the pinned default model for offline first launch. For a small compile-only developer artifact, pass `-PincludeBundledModel=false`; never use that reduced artifact as a test candidate. Release builds additionally require authorized signing; do not use a production key for routine development.

Changes should preserve package identity, imported models, settings, privacy boundaries, accessibility, and update compatibility. Run focused tests and `git diff --check` before requesting review. See [PRODUCT_ROADMAP.md](PRODUCT_ROADMAP.md) for accepted scope and [RELEASE_HANDOFF.md](RELEASE_HANDOFF.md) for immutable test-artifact evidence.

## Credits

- [Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) by NVIDIA and Suno; GGUF conversion by [Handy Computer](https://huggingface.co/handy-computer/parakeet-tdt-0.6b-v3-gguf), licensed under CC BY 4.0.
- [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) by CJ Pais / Handy Computer.

## License

[MIT](LICENSE)
