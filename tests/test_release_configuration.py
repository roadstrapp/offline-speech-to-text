import re
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


class ReleaseConfigurationTest(unittest.TestCase):
    @staticmethod
    def _contrast(first: str, second: str) -> float:
        def luminance(value: str) -> float:
            channels = [int(value[index:index + 2], 16) / 255.0 for index in (1, 3, 5)]
            linear = [channel / 12.92 if channel <= 0.04045
                      else ((channel + 0.055) / 1.055) ** 2.4 for channel in channels]
            return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
        light, dark = sorted((luminance(first), luminance(second)), reverse=True)
        return (light + 0.05) / (dark + 0.05)
    def test_release_identity_and_default_model_contract_are_immutable(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        gradle_properties = (ROOT / "gradle.properties").read_text()
        workflow = (ROOT / ".github/workflows/android_release.yml").read_text()
        self.assertIn('applicationId = "app.offlinespeechtotext"', gradle)
        self.assertIn("versionCode = 39", gradle)
        self.assertIn('versionName = "0.10.0"', gradle)
        self.assertIn('providers.gradleProperty("includeBundledModel")', gradle)
        self.assertIn('.orNull?.toBooleanStrictOrNull() ?: false', gradle)
        self.assertIn('ModelFile("parakeet-tdt-0.6b-v3-Q4_K_M.gguf", 485425504L,', gradle)
        self.assertNotIn("org.gradle.java.home", gradle_properties)
        self.assertNotIn("sed -i '/org.gradle.java.home/d'", workflow)
        self.assertIn('"b68557be1e3c40207fd7c4bd9d63f1d3316b963f15325bfb0cc16a8bb0ffd181"', gradle)
        self.assertIn('resolve/85ac09ea12fc4b1112fa76810059364bc6adc9de', gradle)
        self.assertNotIn("parakeet-tdt_ctc-110m", gradle)

    def test_transcribe_cpp_runtime_stays_on_the_reviewed_lockstep_release(self):
        manifest = (ROOT / "Cargo.toml").read_text()
        lockfile = (ROOT / "Cargo.lock").read_text()
        self.assertIn('transcribe-cpp = "0.2.3"', manifest)
        self.assertRegex(
            lockfile,
            r'name = "transcribe-cpp"\nversion = "0\.2\.3"',
        )
        self.assertRegex(
            lockfile,
            r'name = "transcribe-cpp-sys"\nversion = "0\.2\.3"',
        )

    def test_default_model_is_honestly_labeled_without_delivery_detail_in_readme(self):
        strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        values = {item.attrib["name"]: "".join(item.itertext()) for item in strings.findall("string")}
        self.assertEqual("Parakeet TDT 0.6B v3 Q4", values["models_builtin"])
        readme = (ROOT / "README.md").read_text()
        self.assertIn("Parakeet TDT 0.6B v3 Q4", readme)
        self.assertIn("25 European languages", readme)
        self.assertNotIn("485,425,504 bytes", readme)
        self.assertNotIn("b68557be1e3c40207fd7c4bd9d63f1d3316b963f15325bfb0cc16a8bb0ffd181", readme)

        store_description = (
            ROOT / "fastlane/metadata/android/en-US/full_description.txt"
        ).read_text()
        self.assertIn("does not bundle a speech model", store_description)
        self.assertIn("Download and install", store_description)
        self.assertNotIn("standard release includes", store_description.lower())

    def test_delivery_mode_copy_uses_reviewed_base_strings_in_every_locale(self):
        delivery_copy = {
            "models_builtin_sub",
            "model_desc_parakeet_v3_q8",
        }
        for resource in sorted((ROOT / "app/src/main/res").glob("values-*/strings.xml")):
            with self.subTest(locale=resource.parent.name):
                strings = ET.parse(resource).getroot()
                localized = {item.attrib["name"] for item in strings.findall("string")}
                self.assertFalse(
                    delivery_copy & localized,
                    f"{resource.parent.name} overrides mode-sensitive model delivery copy",
                )

    def test_bundled_default_copy_says_the_model_is_included(self):
        strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        values = {item.attrib["name"]: "".join(item.itertext()) for item in strings.findall("string")}
        self.assertIn("included", values["models_builtin_sub"].lower())
        self.assertNotIn("download separately", values["models_builtin_sub"].lower())
        models = (ROOT / "app/src/main/java/dev/notune/transcribe/ModelsActivity.java").read_text()
        self.assertIn("if (BuildConfig.HAS_BUNDLED_MODEL)", models)
        self.assertIn("R.string.models_builtin_sub", models)
        self.assertIn("R.string.models_builtin_retained_sub", models)
        self.assertIn("models_recommendation_included", models)
        self.assertIn("models_recommendation_included_available", models)
        self.assertIn("models_included", models)
        self.assertIn("models_use_included", models)
        self.assertIn("if (recommendationUsesBundledModel)", models)
        self.assertIn("selectModel(null);", models)
        assets = (ROOT / "src/assets.rs").read_text()
        marker_branch = assets[assets.index("if marker_file.exists()"):
                               assets.index("// Incomplete or missing")]
        self.assertIn("find_gguf(&model_dir)", marker_branch)
        self.assertIn("re-extracting", marker_branch)
        self.assertLess(assets.index("let model_path = match find_gguf(&model_dir) {"),
                        assets.index('std::fs::write(&marker_file, "ok")?;'))
        inventory = (ROOT / "app/src/main/java/dev/notune/transcribe/SpeechModelInventory.java").read_text()
        self.assertIn('new File(filesDir, "builtin-model")', inventory)
        self.assertIn('new File(directory, ".extraction_complete")', inventory)
        self.assertIn('const BUILTIN_MODEL_DIR: &str = "builtin-model";', assets)
        self.assertIn('const EXTRACTION_COMPLETE_MARKER: &str = ".extraction_complete";', assets)

    def test_models_activity_avoids_redundant_delivery_copy(self):
        layout = (ROOT / "app/src/main/res/layout/activity_models.xml").read_text()
        source = (ROOT / "app/src/main/java/dev/notune/transcribe/ModelsActivity.java").read_text()
        self.assertNotIn("text_models_desc", layout)
        self.assertNotIn("findViewById(R.id.text_models_desc)", source)
        self.assertIn("text_model_recommendation", layout)
        self.assertIn("addFamilyHeader(renderedFamily)", source)
        self.assertIn("largestComfortableInFamily", source)
        row = (ROOT / "app/src/main/res/layout/item_model_download.xml").read_text()
        self.assertIn("txt_download_model_fit_badge", row)
        self.assertIn("bg_model_family_recommended", source)


    def test_model_helper_copy_stays_dense_in_every_locale(self):
        for resource in sorted((ROOT / "app/src/main/res").glob("values*/strings.xml")):
            with self.subTest(locale=resource.parent.name):
                values = ET.parse(resource).getroot()
                copy = {item.attrib["name"]: "".join(item.itertext())
                        for item in values.findall("string")}
                for key in ("models_language_desc", "models_threads_desc"):
                    self.assertLessEqual(len(copy[key]), 140)

    def test_ime_recording_exposes_four_compact_accessible_actions(self):
        layout = ET.parse(ROOT / "app/src/main/res/layout/ime_layout.xml").getroot()
        android = "{http://schemas.android.com/apk/res/android}"
        app = "{http://schemas.android.com/apk/res-auto}"
        expected = {
            "@+id/ime_pause_resume",
            "@+id/ime_cancel_recording",
            "@+id/ime_transcribe_keep",
            "@+id/ime_transcribe_switch",
        }
        actions = {
            node.attrib.get(android + "id"): node
            for node in layout.iter()
            if node.attrib.get(android + "id") in expected
        }
        self.assertEqual(expected, set(actions))
        for action in actions.values():
            self.assertGreaterEqual(int(action.attrib[android + "minHeight"].removesuffix("dp")), 64)
            self.assertTrue(action.attrib.get(android + "contentDescription", "").startswith("@string/"))
            self.assertEqual("0dp", action.attrib[android + "layout_width"])

        strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        values = {item.attrib["name"]: "".join(item.itertext()) for item in strings.findall("string")}
        self.assertIn("pause", values["ime_pause_recording"].lower())
        self.assertIn("cancel", values["ime_cancel_recording"].lower())
        self.assertIn("keep", values["ime_transcribe_keep"].lower())
        self.assertIn("switch", values["ime_transcribe_switch"].lower())
        raw_layout = (ROOT / "app/src/main/res/layout/ime_layout.xml").read_text()
        self.assertIn("ime_switch_keyboard", raw_layout)
        self.assertGreater(raw_layout.index("ime_switch_keyboard"),
                           raw_layout.index("ime_backspace"))
        recording = next(node for node in layout.iter()
                         if node.attrib.get(android + "id") == "@+id/ime_recording_actions")
        self.assertEqual("vertical", recording.attrib[android + "orientation"])

        white_icons = expected | {
            "@+id/ime_mic_icon",
            "@+id/ime_space",
            "@+id/ime_enter",
            "@+id/ime_backspace",
            "@+id/ime_switch_keyboard",
        }
        by_id = {node.attrib.get(android + "id"): node for node in layout.iter()}
        for icon_id in white_icons:
            self.assertEqual("@color/white", by_id[icon_id].attrib.get(app + "tint"), icon_id)

        for drawable in ("ic_space.xml", "ic_enter.xml", "ic_keyboard_switch.xml"):
            source = (ROOT / "app/src/main/res/drawable" / drawable).read_text()
            self.assertNotIn("#333333", source, drawable)
            self.assertIn('@android:color/white', source, drawable)

        source = (ROOT / "app/src/main/java/dev/notune/transcribe/RustInputMethodService.java").read_text()
        self.assertIn("cancelRecording();", source)
        self.assertIn("setRecordingPaused(requestedPause)", source)
        self.assertIn("stopTranscribe(false)", source)
        self.assertIn("stopTranscribe(true)", source)
        self.assertIn("ImeTextEdit.isSensitiveEditor", source)
        self.assertIn("micIcon.setColorFilter(getColor(R.color.white))", source)
        self.assertIn("switchKeyboardButton.setVisibility(recording ? View.GONE : View.VISIBLE)",
                      source)
        native = (ROOT / "src/voice_session.rs").read_text()
        self.assertIn("capture_paused.load(Ordering::SeqCst)", native)
        self.assertIn("capture_paused.store(true, Ordering::SeqCst)", native)
        self.assertIn("capture_paused.store(false, Ordering::SeqCst)", native)
        self.assertIn("stream.0.pause()", native)
        self.assertIn("MAX_INTERACTIVE_SESSION_MS", native)
        self.assertIn("startRecording(isAutoStopEnabled())", source)
        self.assertNotIn("setKeepScreenOn(recording)", source)

        window_hidden = source[
            source.index("public void onWindowHidden()") : source.index("public void onStartInput(")
        ]
        self.assertIn("if (isRecording && isStopOnHideEnabled())", window_hidden)
        self.assertIn("stopForRecovery();", window_hidden)
        self.assertIn("isStopOnHideEnabled()", window_hidden)
        finish_input = source[
            source.index("public void onFinishInput()") : source.index("private void stopAndDiscard()")
        ]
        self.assertIn("stopForRecovery();", finish_input)

    def test_ime_recording_session_policy_is_the_only_commit_gate(self):
        source = (ROOT / "app/src/main/java/dev/notune/transcribe/RustInputMethodService.java").read_text()
        self.assertIn("new ImeRecordingSession()", source)
        self.assertIn("recordingSession.canCommitTo", source)
        self.assertIn("recordingSession.discard();", source)
        self.assertIn("recordingSession.onError();", source)
        self.assertNotIn("discardNextTranscription", source)
        self.assertNotIn("pendingCommitText", source)

    def test_ime_app_switch_does_not_misreport_an_empty_auto_start_as_permission_failure(self):
        ime = (ROOT / "app/src/main/java/dev/notune/transcribe/RustInputMethodService.java").read_text()
        native_ime = (ROOT / "src/ime.rs").read_text()
        voice = (ROOT / "src/voice_session.rs").read_text()

        input_view = ime[ime.index("public void onStartInputView("):
                         ime.index("private void tryStartAutomaticRecording()")]
        self.assertIn("if (!restarting && windowVisible) tryStartAutomaticRecording();",
                      input_view)

        auto_start = ime[ime.index("private void tryStartAutomaticRecording()"):
                         ime.index("@Override\n    public void onFinishInput()")]
        self.assertIn("automaticStartInFlight || isRecording || processingResult", auto_start)
        self.assertIn("automaticStartInFlight = true;", auto_start)
        self.assertIn("finally", auto_start)
        self.assertIn("automaticStartInFlight = false;", auto_start)

        stop_result = ime[ime.index("private void stopForResult("):
                          ime.index("private void releasePauseAudio()")]
        self.assertIn("boolean lifecycleHandoff", stop_result)
        self.assertIn("capturedAudio = finishRecording();", stop_result)
        self.assertIn("if (!capturedAudio)", stop_result)
        self.assertIn('lastStatus = "Ready";', stop_result)
        self.assertIn("pendingSwitchBack && !lifecycleHandoff", stop_result)
        self.assertIn("switchToOtherInputMethod();", stop_result)
        self.assertIn("R.string.ime_no_audio_captured", stop_result)

        manual_stop = ime[ime.index("private void stopTranscribe("):
                          ime.index("/** Stop capture and keep its result")]
        recovery_stop = ime[ime.index("private void stopForRecovery()"):
                            ime.index("private void stopForResult(")]
        self.assertIn("stopForResult(switchKeyboard", manual_stop)
        self.assertIn(", false);", manual_stop)
        self.assertIn("stopForResult(false, true);", recovery_stop)

        self.assertIn(
            "Java_dev_notune_transcribe_RustInputMethodService_finishRecording",
            native_ime,
        )
        self.assertIn("voice_session::end_recording_capture(state)", native_ime)
        self.assertIn("voice_session::cleanup_session(state);", native_ime)
        self.assertNotIn("Check microphone permissions", voice)
        self.assertIn("Error: no audio was captured. Try again.", voice)

    def test_cancelled_capture_releases_ownership_before_native_inference_winds_down(self):
        voice = (ROOT / "src/voice_session.rs").read_text()
        service = (ROOT / "src/recog_service.rs").read_text()
        wrapper = (ROOT / "app/src/main/java/dev/notune/transcribe/VoiceRecognitionService.java").read_text()
        voice_cancel = voice[voice.index("pub fn cleanup_session"):
                             voice.index("fn release_owned_session")]
        service_cancel = service[
            service.index("Java_dev_notune_transcribe_VoiceRecognitionService_cancelNative"):
            service.index("Java_dev_notune_transcribe_VoiceRecognitionService_destroyNative")]
        self.assertLess(voice_cancel.index("release_owned_session(state)"),
                        voice_cancel.index("background.cancel()"))
        self.assertLess(service_cancel.index("clear_session(&shared)"),
                        service_cancel.index("std::thread::spawn"))
        self.assertGreaterEqual(service_cancel.count("clear_session(&shared)"), 2)
        self.assertIn("model access stays", service_cancel)
        start = wrapper[wrapper.index("protected void onStartListening"):
                        wrapper.index("private void startListeningFromCallerAudio")]
        self.assertLess(start.index("cancelNative();"), start.index("mCallback = callback;"))
        self.assertIn("private volatile Callback mCallback;", wrapper)
        self.assertIn("if (callback != mCallback) return;", wrapper)
        self.assertIn("if (mCallback == cb) mCallback = null;", wrapper)

    def test_capture_and_live_subtitle_power_hot_paths_are_bounded(self):
        popup = (ROOT / "app/src/main/java/dev/notune/transcribe/RecognizeActivity.java").read_text()
        subtitles_java = (ROOT / "app/src/main/java/dev/notune/transcribe/LiveSubtitleService.java").read_text()
        voice = (ROOT / "src/voice_session.rs").read_text()
        service = (ROOT / "src/recog_service.rs").read_text()
        subtitles = (ROOT / "src/subtitle.rs").read_text()

        self.assertNotIn("FLAG_KEEP_SCREEN_ON", popup)
        self.assertNotIn("FLAG_KEEP_SCREEN_ON", subtitles_java)
        self.assertNotIn("Log.d(", subtitles_java)
        self.assertIn("MANUAL_MONITOR_MS: u64 = 1000", voice)
        self.assertIn("AUTO_STOP_MONITOR_MS: u64 = 250", voice)
        self.assertIn("health_callback_count", voice)
        self.assertNotIn("last_level_sent: Arc<Mutex", voice)
        self.assertIn("ENDPOINT_MONITOR_MS: u64 = 250", service)
        self.assertIn("struct AudioAnalyzer", service)
        self.assertNotIn("last_level_sent: Mutex", service)
        self.assertIn("input_scratch: Vec<f32>", subtitles)
        self.assertIn("preroll: VecDeque<f32>", subtitles)
        self.assertNotIn("let mut input = vec!", subtitles)

    def test_android_8_and_9_paths_guard_newer_platform_apis(self):
        activity = (ROOT / "app/src/main/java/dev/notune/transcribe/LiveSubtitleActivity.java").read_text()
        service = (ROOT / "app/src/main/java/dev/notune/transcribe/LiveSubtitleService.java").read_text()
        ime = (ROOT / "app/src/main/java/dev/notune/transcribe/RustInputMethodService.java").read_text()
        gradle = (ROOT / "app/build.gradle.kts").read_text()

        self.assertIn("Build.VERSION.SDK_INT < Build.VERSION_CODES.Q", activity)
        self.assertIn("requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}", activity)
        self.assertIn("Build.VERSION.SDK_INT < Build.VERSION_CODES.Q", service)
        self.assertIn("@RequiresApi(Build.VERSION_CODES.Q)", service)
        self.assertIn("checkSelfPermission(Manifest.permission.RECORD_AUDIO)", service)
        self.assertIn("private void switchToOtherInputMethod()", ime)
        self.assertIn("Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P", ime)
        self.assertIn("imm.showInputMethodPicker()", ime)
        self.assertEqual(1, ime.count("switchToPreviousInputMethod()"))
        self.assertIn('disable += "MissingTranslation"', gradle)

    def test_backspace_hold_drag_is_preview_only_until_release_and_restores_on_cancel(self):
        source = (ROOT / "app/src/main/java/dev/notune/transcribe/RustInputMethodService.java").read_text()
        self.assertIn("ImeTextEdit.selectionForClusterDelta", source)
        self.assertIn("beginDeletePreview", source)
        self.assertIn("finishDeletePreview", source)
        self.assertIn("cancelDeletePreview", source)
        self.assertIn("MotionEvent.ACTION_CANCEL", source)
        self.assertIn("DELETE_PREVIEW_DELAY = 180", source)
        self.assertIn("getScaledTouchSlop()", source)

        preview = source[source.index("private void updateDeletePreview(") :
                         source.index("private void finishDeletePreview(")]
        self.assertIn("setSelection(deletePreviewStart, deletePreviewEnd)", preview)
        self.assertIn("deleteIssuedSelections", preview)
        self.assertNotIn("setComposingRegion", preview)
        finish = source[source.index("private void finishDeletePreview(") :
                        source.index("private void sendOneTapBackspace(")]
        self.assertIn("ic.commitText(\"\", 1)", finish)
        self.assertIn("ic.beginBatchEdit()", finish)
        self.assertIn("deleteSnapshotMatchesIssuedSelection()", finish)
        cancel = source[source.index("private void cancelDeletePreview(") :
                        source.index("private void restoreDeleteSelection(")]
        self.assertNotIn("finishComposingText()", cancel)

    def test_transcription_survives_wrapper_replacement_and_is_staged_before_delivery(self):
        source = (ROOT / "app/src/main/java/dev/notune/transcribe/RustInputMethodService.java").read_text()
        session = (ROOT / "app/src/main/java/dev/notune/transcribe/ImeRecordingSession.java").read_text()
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        staging = source.index("PendingImeTranscriptStore.saveIfEmpty(this, text)")
        insertion = source.index("commitTranscribedText(current, committed)")
        self.assertLess(staging, insertion)
        self.assertIn("editorGeneration == originGeneration", session)
        self.assertIn("canDeliverToCurrent", session)
        self.assertNotIn("current == origin", session)
        self.assertIn("DELIVERY_RETRY_LIMIT = 12", source)
        self.assertIn("DELIVERY_RETRY_DELAY_MS = 125L", source)
        self.assertIn("recordingSession.canDeliverToCurrent", source)
        self.assertNotIn("requestedTarget", source)
        self.assertIn("if (!ic.commitText(committed, 1)) return false", source)
        self.assertIn("ImeTranscriptRecoveryActivity", manifest)
        self.assertIn("openTranscriptRecovery()", source)
        recovery_intent = source[source.index("private void openTranscriptRecovery()"):
                                 source.index("private void copyEmergencyTranscript")]
        self.assertNotIn("putExtra(\"text\"", recovery_intent)
        self.assertNotIn("putExtra(\"transcript\"", recovery_intent)
        self.assertIn("Toast.makeText(getApplicationContext()", source)
        delivery = source[source.index("private void deliverStagedTranscription"):
                          source.index("private void finishTranscriptDelivery")]
        self.assertIn("mainHandler.postDelayed", delivery)
        self.assertNotIn("openTranscriptRecovery", delivery)
        completion = source[source.index("private void finishTranscriptDelivery"):
                            source.index("private void switchToOtherInputMethod")]
        self.assertNotIn("openTranscriptRecovery", completion)

    def test_default_model_import_is_verified_before_activation(self):
        source = (ROOT / "app/src/main/java/dev/notune/transcribe/ModelImportTask.java").read_text()
        verification = source.index("DefaultModelContract.matches")
        activation = source.index("ModelInstaller.installImported")
        self.assertLess(verification, activation)
        self.assertIn("MessageDigest.getInstance(\"SHA-256\")", source)
        self.assertIn("DEFAULT_MODEL_MISMATCH", source)

    def test_amoled_black_has_a_safe_default_resource(self):
        colors = ET.parse(ROOT / "app/src/main/res/values/colors.xml").getroot()
        values = {item.attrib["name"]: "".join(item.itertext()) for item in colors.findall("color")}
        self.assertEqual("#000000", values["amoled_black"])

    def test_model_selection_is_before_secondary_features(self):
        layout = (ROOT / "app/src/main/res/layout/activity_main.xml").read_text()
        model = layout.index('@+id/btn_models')
        self.assertLess(model, layout.index('@+id/btn_voice_try'))
        self.assertLess(model, layout.index('@+id/btn_subs_start'))

    def test_compact_startup_does_not_open_a_missing_builtin_model_asset(self):
        assets = (ROOT / "src/assets.rs").read_text()
        main = (ROOT / "app/src/main/java/dev/notune/transcribe/MainActivity.java").read_text()

        missing_asset_guard = assets.index(
            "list_asset_children(env, &asset_manager_obj, BUILTIN_MODEL_DIR)?.is_empty()")
        recursive_copy = assets.index(
            "copy_assets_recursively(env, &asset_manager_obj, BUILTIN_MODEL_DIR, &base_path)")
        self.assertLess(missing_asset_guard, recursive_copy)
        self.assertIn("no bundled model is packaged in this build", assets)
        self.assertIn("env.exception_clear()", assets)

        helper = main[main.index("private void maybeStartNativeModelLoad()"):
                      main.index("private void updateVoiceInputStatus()")]
        self.assertIn("if (!hasUsableSpeechModel())", helper)
        self.assertIn("return;", helper)
        self.assertLess(helper.index("if (!hasUsableSpeechModel())"),
                        helper.index("initNative(this);"))
        self.assertGreaterEqual(main.count("maybeStartNativeModelLoad();"), 2)
        self.assertIn("statusText.setText(R.string.status_model_required)", main)
        self.assertIn("benchButton.setEnabled(false)", main)

    def test_home_accessibility_supports_api_26_and_48dp_touch_targets(self):
        layout_path = ROOT / "app/src/main/res/layout/activity_main.xml"
        root = ET.parse(layout_path).getroot()
        android = "{http://schemas.android.com/apk/res/android}"
        headings = [node for node in root.iter()
                    if node.attrib.get(android + "accessibilityHeading") == "true"]
        self.assertEqual(7, len(headings))
        self.assertTrue(all(node.attrib.get(android + "id", "").startswith("@+id/")
                            for node in headings))

        activity = (ROOT / "app/src/main/java/dev/notune/transcribe/MainActivity.java").read_text()
        self.assertIn("ViewCompat.setAccessibilityHeading", activity)
        for heading in headings:
            self.assertIn("R.id." + heading.attrib[android + "id"].removeprefix("@+id/"), activity)
        self.assertIn("MIN_TOUCH_TARGET_DP = 48", activity)
        self.assertIn("view.isClickable() || view instanceof Checkable", activity)

    def test_night_theme_uses_true_black_and_dynamic_color_cannot_override_it(self):
        night_colors = (ROOT / "app/src/main/res/values-night/colors.xml").read_text()
        night_styles = (ROOT / "app/src/main/res/values-night/styles.xml").read_text()
        app = (ROOT / "app/src/main/java/dev/notune/transcribe/App.java").read_text()
        self.assertIn('<color name="amoled_black">#000000</color>', night_colors)
        self.assertIn('<item name="colorSurface">@color/colorSurface</item>', night_styles)
        self.assertIn('<color name="colorSurface">#000000</color>', night_colors)
        self.assertIn('<item name="android:colorBackground">@color/background</item>', night_styles)
        self.assertNotIn("DynamicColors.applyToActivitiesIfAvailable", app)

    def test_ime_night_theme_cannot_be_overridden_by_dynamic_color(self):
        ime = (ROOT / "app/src/main/java/dev/notune/transcribe/RustInputMethodService.java").read_text()
        self.assertNotIn("DynamicColors.wrapContextIfAvailable", ime)
        self.assertNotIn("com.google.android.material.color.DynamicColors", ime)
        self.assertIn("new ContextThemeWrapper(night, R.style.AppTheme)", ime)

    def test_successful_import_activates_and_reloads_model(self):
        source = (ROOT / "app/src/main/java/dev/notune/transcribe/ModelsActivity.java").read_text()
        task = (ROOT / "app/src/main/java/dev/notune/transcribe/ModelImportTask.java").read_text()
        self.assertIn("ModelInstaller.installImported", task)
        success_branch = source[source.index(
            "if (snapshot.state == ModelImportLifecycle.State.COMPLETE)"):
            source.index("} else {", source.index(
                "if (snapshot.state == ModelImportLifecycle.State.COMPLETE)"))]
        self.assertIn("reloadModelNative", success_branch)

    def test_setup_first_information_architecture_and_recovery_controls(self):
        layout = ET.parse(ROOT / "app/src/main/res/layout/activity_main.xml").getroot()
        android = "{http://schemas.android.com/apk/res/android}"
        ids = {
            node.attrib[android + "id"]
            for node in layout.iter()
            if android + "id" in node.attrib
        }
        for expected in (
            "@+id/home_content", "@+id/card_setup", "@+id/card_models",
            "@+id/text_ime_status", "@+id/btn_ime_picker",
            "@+id/btn_ime_settings", "@+id/text_version",
        ):
            self.assertIn(expected, ids)

        main = (ROOT / "app/src/main/java/dev/notune/transcribe/MainActivity.java").read_text()
        layout_text = (ROOT / "app/src/main/res/layout/activity_main.xml").read_text()
        self.assertLess(layout_text.index('@+id/card_models'), layout_text.index('@+id/card_setup'))
        self.assertNotIn("homeContent.addView", main)
        self.assertIn("SetupState.microphone", main)
        self.assertIn("SetupState.ime", main)
        self.assertIn("Settings.ACTION_APPLICATION_DETAILS_SETTINGS", main)
        self.assertIn("Settings.ACTION_INPUT_METHOD_SETTINGS", main)
        self.assertIn("showInputMethodPicker", main)

    def test_home_is_dense_without_losing_any_action(self):
        layout_text = (ROOT / "app/src/main/res/layout/activity_main.xml").read_text()
        for obsolete in (
            "@string/welcome_subtitle",
            "@string/setup_migration_explanation",
            "@string/section_history",
            "@string/history_not_stored",
            "@string/models_desc",
        ):
            self.assertNotIn(obsolete, layout_text)

        for action in (
            "btn_models", "btn_voice_grant", "btn_voice_try", "btn_voice_help",
            "btn_ime_picker", "btn_ime_settings", "btn_subs_start",
            "btn_subs_advanced", "btn_benchmark", "switch_auto_record",
            "switch_select_transcription", "switch_pause_audio",
            "switch_record_background", "switch_auto_stop", "rg_subtitle_lines",
            "rg_theme", "switch_diagnostic_logging", "btn_clear_diagnostic_log",
            "btn_source", "btn_license",
        ):
            self.assertIn(f'@+id/{action}', layout_text)

        self.assertIn('@+id/btn_more_settings', layout_text)
        self.assertIn('@+id/advanced_home_sections', layout_text)
        self.assertGreater(layout_text.index('@+id/btn_subs_advanced'),
                           layout_text.index('@+id/advanced_home_sections'))
        self.assertIn('android:visibility="gone"', layout_text[
            layout_text.index('@+id/advanced_home_sections'):])
        main = (ROOT / "app/src/main/java/dev/notune/transcribe/MainActivity.java").read_text()
        self.assertIn("advancedSections.setVisibility", main)

        self.assertLessEqual(layout_text.count("MaterialCardView"), 14)

    def test_home_accessibility_contract_is_explicit_and_dynamic_type_safe(self):
        root = ET.parse(ROOT / "app/src/main/res/layout/activity_main.xml").getroot()
        android = "{http://schemas.android.com/apk/res/android}"
        interactive = {"Button", "RadioButton", "MaterialButton", "MaterialRadioButton", "MaterialSwitch"}
        for node in root.iter():
            kind = node.tag.rsplit("}", 1)[-1].rsplit(".", 1)[-1]
            if kind in interactive:
                height = node.attrib.get(android + "layout_height")
                self.assertNotRegex(height or "", r"^(?:[0-4]?[0-7])dp$")
            if kind == "ImageView":
                self.assertEqual("no", node.attrib.get(android + "importantForAccessibility"))

        headings = [
            node for node in root.iter("TextView")
            if node.attrib.get(android + "accessibilityHeading") == "true"
        ]
        self.assertGreaterEqual(len(headings), 6)

    def test_idle_ime_record_control_matches_keyboard_key_shape(self):
        android = "{http://schemas.android.com/apk/res/android}"
        layout = ET.parse(ROOT / "app/src/main/res/layout/ime_layout.xml").getroot()
        record = next(
            node for node in layout.iter()
            if node.attrib.get(android + "id") == "@+id/ime_record_key"
        )
        self.assertEqual("160dp", record.attrib.get(android + "layout_width"))
        self.assertEqual("88dp", record.attrib.get(android + "layout_height"))
        self.assertEqual("@drawable/bg_ime_record", record.attrib.get(android + "background"))

        record_background = ET.parse(
            ROOT / "app/src/main/res/drawable/bg_ime_record.xml"
        ).getroot()
        self.assertEqual("rectangle", record_background.attrib.get(android + "shape"))
        corners = next(
            child for child in record_background
            if child.tag.rsplit("}", 1)[-1] == "corners"
        )
        self.assertEqual("12dp", corners.attrib.get(android + "radius"))

    def test_voice_surfaces_show_progress_around_microphone_while_processing(self):
        ime_layout = (ROOT / "app/src/main/res/layout/ime_layout.xml").read_text()
        popup = (ROOT / "app/src/main/res/layout/recognize_activity.xml").read_text()
        side = (ROOT / "app/src/main/res/layout/recognize_activity_side.xml").read_text()
        ime = (ROOT / "app/src/main/java/dev/notune/transcribe/RustInputMethodService.java").read_text()
        activity = (ROOT / "app/src/main/java/dev/notune/transcribe/RecognizeActivity.java").read_text()
        self.assertIn('@+id/ime_processing_ring', ime_layout)
        self.assertIn('@+id/mic_processing_ring', popup)
        self.assertIn('@+id/mic_processing_ring', side)
        self.assertIn('processingResult = true', ime)
        self.assertIn('isProcessing && !recoveryPending ? View.VISIBLE : View.GONE', ime)
        self.assertIn('awaitingResult = true', activity)
        self.assertIn(
            'showProcessing(awaitingResult || isProcessingStatus(s))',
            activity,
        )

    def test_microphone_finalization_preserves_audio_and_adds_decoder_tail(self):
        helper = (ROOT / "src/audio_finalization.rs").read_text()
        background = (ROOT / "src/background_transcription.rs").read_text()
        self.assertIn("FINAL_DECODE_SILENCE_MS: usize = 500", helper)
        self.assertIn("if samples.is_empty()", helper)
        self.assertIn("samples.resize", helper)
        self.assertEqual(1, background.count("append_final_decode_silence(&mut audio)"))
        self.assertNotIn("audio.truncate", background)
        self.assertNotIn("retry_complete_recording", background)
        self.assertLess(background.index("append_final_decode_silence(&mut audio)"),
                        background.index("transcribe_complete(&eng"))

    def test_complete_dictation_is_never_split_on_a_pause(self):
        capture = (ROOT / "src/background_chunking.rs").read_text()
        background = (ROOT / "src/background_transcription.rs").read_text()
        engine = (ROOT / "src/engine.rs").read_text()
        voice = (ROOT / "src/voice_session.rs").read_text()
        recognizer = (ROOT / "src/recog_service.rs").read_text()
        self.assertIn("fn pauses_never_split_the_utterance", capture)
        self.assertNotIn("trailing_silence_samples", capture)
        self.assertNotIn("Command::Chunk", background)
        self.assertNotIn("active_chunk_plan", voice + recognizer + engine)
        self.assertIn("let piece = self.run(samples, &job.run_ext)?", engine)
        self.assertIn("if let Some(max_window_samples) = self.bounded_window_samples", engine)
        self.assertIn("crate::long_audio::stitch", engine)
        self.assertIn("crate::long_audio::MIN_WINDOW_SAMPLES", engine)
        self.assertNotIn("while !rest.is_empty()", engine)

    def test_fixed_amber_palette_and_launcher_icon_have_no_blue_fallback(self):
        color_files = [
            ROOT / "app/src/main/res/values/colors.xml",
            ROOT / "app/src/main/res/values-night/colors.xml",
        ]
        combined = "\n".join(path.read_text() for path in color_files)
        self.assertIn('<color name="colorPrimary">#FFB300</color>', combined)
        self.assertNotIn("#2196F3", combined.upper())
        self.assertNotIn("#1976D2", combined.upper())

        styles = "\n".join((ROOT / path).read_text() for path in (
            "app/src/main/res/values/styles.xml",
            "app/src/main/res/values-night/styles.xml",
        ))
        for role in ("colorPrimary", "colorOnPrimary", "colorPrimaryContainer",
                     "colorOnPrimaryContainer", "colorSecondary", "colorOnSecondary",
                     "colorTertiary", "colorOnTertiary", "colorTertiaryContainer",
                     "colorOnTertiaryContainer"):
            self.assertIn(f'<item name="{role}">', styles)

        self.assertFalse((ROOT / "app/src/main/res/drawable/icon.png").exists())
        foreground = (ROOT / "app/src/main/res/drawable/icon_foreground.xml").read_text().lower()
        adaptive = (ROOT / "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml").read_text().lower()
        self.assertIn('android:fillcolor="#ffffffff"', foreground)
        self.assertNotIn("#ffb300", foreground)
        self.assertIn('@color/launcher_black', adaptive)

    def test_diagnostic_logging_ui_documents_privacy_and_clear_behavior(self):
        strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        values = {item.attrib["name"]: "".join(item.itertext()) for item in strings.findall("string")}
        description = values["setting_diagnostic_logging_desc"].lower()
        for phrase in ("off by default", "never includes audio", "recognized text", "64 kb"):
            self.assertIn(phrase, description)
        self.assertIn("deletes", values["diagnostic_log_clear_desc"].lower())
        self.assertIn("content-free", values["diagnostic_log_export_desc"].lower())

        source = (ROOT / "app/src/main/java/dev/notune/transcribe/MainActivity.java").read_text()
        self.assertIn("DiagnosticLog.setEnabled", source)
        self.assertIn("DiagnosticLog.clear", source)
        self.assertIn("DiagnosticLog.snapshot", source)
        self.assertIn("Intent.ACTION_CREATE_DOCUMENT", source)
        self.assertIn("Intent.ACTION_SEND", source)
        self.assertIn("FileProvider.getUriForFile", source)
        self.assertIn('new Thread(runnable, "diagnostic-export")', source)
        self.assertIn("diagnosticIo.execute", source)
        self.assertIn("ostt-diagnostics-share-", source)
        self.assertIn("pruneSharedDiagnosticFiles", source)
        self.assertNotIn('new File(directory, "ostt-diagnostics.txt")', source)
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        self.assertIn("androidx.core.content.FileProvider", manifest)
        provider_paths = (ROOT / "app/src/main/res/xml/file_provider_paths.xml").read_text()
        self.assertIn('path="diagnostics/"', provider_paths)
        self.assertNotIn("files-path", provider_paths)
        diagnostic = (ROOT / "app/src/main/java/dev/notune/transcribe/DiagnosticLog.java").read_text()
        self.assertIn("COMPACT_TO_BYTES = 48L * 1024L", diagnostic)
        self.assertIn("existingSize + line.length <= MAX_BYTES", diagnostic)
        self.assertIn("channel.position(existingSize)", diagnostic)
        for metric in ("audio_ms", "paused_ms", "cpu_threads"):
            self.assertIn('case "' + metric + '":', diagnostic)
        self.assertIn('R.id.btn_source', source)
        self.assertIn('R.id.btn_license', source)
        self.assertIn('Intent.ACTION_VIEW', source)
        self.assertIn('catch (ActivityNotFoundException', source)
        self.assertIn('R.string.models_no_browser', source)

    def test_content_bearing_runtime_loggers_are_disabled(self):
        for source_path in (ROOT / "src").glob("*.rs"):
            source = source_path.read_text()
            self.assertNotIn("LevelFilter::Info", source, source_path.name)
            self.assertNotIn("LevelFilter::Debug", source, source_path.name)

        ime = (ROOT / "app/src/main/java/dev/notune/transcribe/RustInputMethodService.java").read_text()
        recognition = (ROOT / "app/src/main/java/dev/notune/transcribe/VoiceRecognitionService.java").read_text()
        self.assertNotIn('"Status: " + status', ime)
        self.assertNotIn('"engine: " + status', recognition)

    def test_launcher_store_art_matches_black_circle_white_mic_contract(self):
        from PIL import Image

        image = Image.open(ROOT / "fastlane/metadata/android/en-US/images/icon.png").convert("RGBA")
        self.assertEqual((1024, 1024), image.size)
        for corner in ((0, 0), (1023, 0), (0, 1023), (1023, 1023)):
            self.assertEqual(0, image.getpixel(corner)[3])
        self.assertEqual((0, 0, 0, 255), image.getpixel((512, 64)))
        self.assertEqual((255, 255, 255, 255), image.getpixel((512, 300)))
        self.assertEqual((0, 0, 0, 255), image.getpixel((250, 512)))

    def test_launcher_manifest_resolves_to_adaptive_and_monochrome_assets(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        self.assertIn('android:icon="@mipmap/ic_launcher"', manifest)
        self.assertIn('android:roundIcon="@mipmap/ic_launcher"', manifest)
        adaptive = (ROOT / "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml").read_text()
        themed = (ROOT / "app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml").read_text()
        self.assertIn('android:drawable="@color/launcher_black"', adaptive)
        self.assertIn('android:drawable="@drawable/icon_foreground"', adaptive)
        self.assertIn('<monochrome android:drawable="@drawable/icon_foreground"', themed)
        for path in (ROOT / "app/src/main").rglob("*"):
            if path.is_file() and path.suffix in {".xml", ".java", ".kt"}:
                self.assertNotRegex(path.read_text(), r'@drawable/icon(?:["<\s])', str(path))

    def test_amber_roles_meet_contrast_in_light_and_dark_modes(self):
        def color_map(path):
            return {node.attrib["name"]: (node.text or "").strip()
                    for node in ET.parse(path).getroot().findall("color")}

        light = color_map(ROOT / "app/src/main/res/values/colors.xml")
        night = color_map(ROOT / "app/src/main/res/values-night/colors.xml")
        theme_roles = {
            "colorPrimary", "colorOnPrimary", "colorPrimaryContainer",
            "colorOnPrimaryContainer", "colorSecondary", "colorOnSecondary",
            "colorSecondaryContainer", "colorOnSecondaryContainer", "colorTertiary",
            "colorOnTertiary", "colorTertiaryContainer", "colorOnTertiaryContainer",
            "colorSurface", "colorOnSurface", "colorSurfaceInverse", "colorOnSurfaceInverse",
            "colorSurfaceVariant", "colorOnSurfaceVariant",
            "colorSurfaceContainerLowest", "colorSurfaceContainerLow", "colorSurfaceContainer",
            "colorSurfaceContainerHigh", "colorSurfaceContainerHighest", "colorOutline",
            "colorOutlineVariant",
        }
        self.assertTrue(theme_roles.issubset(light), "light palette must define every core role")
        self.assertTrue(theme_roles.issubset(night), "night palette must define every core role")
        for styles_path in (
                ROOT / "app/src/main/res/values/styles.xml",
                ROOT / "app/src/main/res/values-night/styles.xml"):
            styles = styles_path.read_text()
            for role in theme_roles:
                self.assertIn(f'<item name="{role}">@color/{role}</item>', styles, str(styles_path))
            self.assertIn('<item name="android:colorBackground">@color/background</item>', styles)

        for palette in (light, night):
            for foreground, background in (
                    ("colorPrimary", "background"),
                    ("colorPrimary", "card_bg"),
                    ("colorOnPrimary", "colorPrimary"),
                    ("colorOnPrimaryContainer", "colorPrimaryContainer"),
                    ("colorOnSecondary", "colorSecondary"),
                    ("colorOnSecondaryContainer", "colorSecondaryContainer"),
                    ("colorOnTertiary", "colorTertiary"),
                    ("colorOnTertiaryContainer", "colorTertiaryContainer"),
                    ("colorOnSurface", "colorSurface"),
                    ("colorOnSurfaceInverse", "colorSurfaceInverse"),
                    ("colorOnSurfaceVariant", "colorSurfaceVariant")):
                self.assertGreaterEqual(self._contrast(palette[foreground], palette[background]), 4.5,
                                        f"{foreground} must contrast with {background}")
            for role in ("colorSurfaceInverse", "colorOnSurfaceInverse"):
                red, green, blue = (int(palette[role][index:index + 2], 16)
                                    for index in (1, 3, 5))
                self.assertGreaterEqual(red, blue, f"{role} must stay warm or neutral")

    def test_privacy_and_migration_copy_is_truthful(self):
        strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        values = {item.attrib["name"]: "".join(item.itertext()) for item in strings.findall("string")}
        self.assertNotIn("setup_migration_explanation", values)
        self.assertIn("no transcription history is stored", values["history_not_stored"].lower())
        self.assertIn("no analytics", values["privacy_about_body"].lower())
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        self.assertIn("android.permission.INTERNET", manifest)
        self.assertIn('android:allowBackup="false"', manifest)
        self.assertIn("only after you choose a model download", values["privacy_about_body"].lower().replace("begins ", ""))

    def test_verified_download_is_explicit_offline_first_and_capability_honest(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        activity = (ROOT / "app/src/main/java/dev/notune/transcribe/ModelsActivity.java").read_text()
        downloader = (ROOT / "app/src/main/java/dev/notune/transcribe/ModelDownloadTask.java").read_text()
        catalog = (ROOT / "app/src/main/java/dev/notune/transcribe/ModelCatalog.java").read_text()
        app = (ROOT / "app/src/main/java/dev/notune/transcribe/App.java").read_text()
        main = (ROOT / "app/src/main/java/dev/notune/transcribe/MainActivity.java").read_text()
        self.assertIn("android.permission.INTERNET", manifest)
        self.assertNotIn("HttpURLConnection", app + main)
        strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        values = {item.attrib["name"]: "".join(item.itertext()) for item in strings.findall("string")}
        self.assertIn("downloadButton.setOnClickListener", activity)
        self.assertIn("if (recommendationUsesBundledModel)", activity)
        self.assertIn("selectModel(null);", activity)
        self.assertIn("beginDownload(recommendedModel, DOWNLOAD_ORIGIN_RECOMMENDED);", activity)
        self.assertIn('findViewById(R.id.btn_model_links).setOnClickListener(v -> showLinksDialog())',
                      activity)
        self.assertIn("Browse model websites", values["models_links"])
        self.assertNotIn("Where to get models", values["models_links"])
        destroy = activity[activity.index("protected void onDestroy()"):
                           activity.index("private void updateActiveSummary")]
        self.assertIn("ModelDownloadLifecycle.shouldCancelOnDestroy(isChangingConfigurations())",
                      destroy)
        summary = activity[activity.index("private void updateActiveSummary"):
                           activity.index("private void addModelRow")]
        self.assertNotIn("models_integrity_verified", summary)
        self.assertIn("ModelDownloadPolicy.acceptArtifact", downloader)
        self.assertIn("ModelInstaller.installVerified", downloader)
        self.assertIn("download.lock", downloader)
        self.assertIn("tryLock()", downloader)
        self.assertIn("setRequestProperty(\"Range\"", downloader)
        self.assertIn("setRequestProperty(\"If-Range\"", downloader)
        self.assertEqual(3, catalog.count("ModelDescriptor.Provenance.EXACT_PINNED"))
        self.assertIn("SenseVoice", catalog)
        self.assertIn("Whisper", catalog)
        self.assertIn("parakeet-tdt-1.1b", catalog)
        self.assertIn("parakeet-primeline-Q4_K_M.gguf", catalog)
        self.assertIn("transcribe.cpp 0.2.3", catalog)
        self.assertNotIn("transcribe.cpp 0.1.3", catalog)
        self.assertIn("ModelCatalog.verifiedDownloads()", activity)
        self.assertIn("startDownload(model, origin)", activity)
        self.assertIn("hasUsableNetworkAccess()", activity)
        self.assertIn("showNetworkAccessDialog(model, origin, null)", activity)
        self.assertIn("showRemoveNetworkAccessDialog()", activity)
        self.assertIn("device.totalRamBytes >= model.minimumRamBytes", activity)
        self.assertIn("device.freeStorageBytes >= model.minimumFreeBytes", activity)
        models_layout = (ROOT / "app/src/main/res/layout/activity_models.xml").read_text()
        self.assertIn('@+id/spinner_catalog_family', models_layout)
        self.assertIn('@+id/download_catalog', models_layout)
        self.assertIn('@+id/recommended_download_progress_area', models_layout)
        catalog_row = (ROOT / "app/src/main/res/layout/item_model_download.xml").read_text()
        self.assertIn('@+id/model_download_progress_area', catalog_row)
        self.assertIn('@+id/model_download_progress', catalog_row)
        engine = (ROOT / "src/engine.rs").read_text()
        self.assertIn("std::panic::catch_unwind", engine)
        self.assertIn('panic = "unwind"', (ROOT / "Cargo.toml").read_text())
        self.assertNotIn("check_cpu_features", engine)
        recommendation_method = activity[activity.index("private void updateRecommendation"):
                                         activity.index("private ModelRecommendation.DeviceProfile")]
        self.assertLess(recommendation_method.index(
                            "if (result.status == ModelRecommendation.Status.RECOMMENDED)"),
                        recommendation_method.index("models_recommendation_installed"))
        no_match = recommendation_method[recommendation_method.index("} else {"):]
        self.assertIn("models_recommendation_none", no_match)
        self.assertIn("downloadButton.setText(R.string.models_download_and_use)", no_match)

        selection = activity[activity.index("private void selectModel"):
                             activity.index("private void confirmDelete")]
        self.assertIn("ImportedModelSelection.activate", selection)
        self.assertIn("ImportedModelSelection.deactivate", selection)
        self.assertNotIn('writeConfig("active_model"', selection)
        self.assertNotIn('writeConfig("active_model"', activity)
        self.assertIn("if (f.isFile())", activity)
        self.assertIn("IMPORT_LIFECYCLE.isRunning()", activity)
        self.assertIn("IMPORT_LIFECYCLE.attach(importObserver)", activity)

    def test_model_guidance_and_network_copy_are_honest(self):
        activity = (ROOT / "app/src/main/java/dev/notune/transcribe/ModelsActivity.java").read_text()
        main = (ROOT / "app/src/main/java/dev/notune/transcribe/MainActivity.java").read_text()
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        values = {item.attrib["name"]: "".join(item.itertext()) for item in strings.findall("string")}
        self.assertIn("android.permission.ACCESS_NETWORK_STATE", manifest)
        self.assertIn("ModelRecommendation.Goal", activity)
        self.assertIn("snapshot.modelId.equals(recommendedModel.id)", activity)
        self.assertIn("NET_CAPABILITY_INTERNET", activity)
        self.assertNotIn("NET_CAPABILITY_VALIDATED", activity)
        self.assertNotIn("benchmarkModelsNative", activity)
        self.assertIn("snackbar(getString(R.string.models_download_failed, failure))", activity)
        self.assertIn("models_retry_download", activity)
        self.assertIn("ModelDownloadLifecycle.State.FAILED", activity)
        self.assertIn("protected void onStart()", activity)
        self.assertIn("DOWNLOAD_LIFECYCLE.attach(downloadObserver)", activity)
        self.assertIn("protected void onStop()", activity)
        self.assertIn("DOWNLOAD_LIFECYCLE.detach(downloadObserver)", activity)
        self.assertNotIn("DOWNLOAD_LIFECYCLE.clearTerminal();\n                importButton", activity)
        goal_listener = activity[activity.index("goalSpinner.setOnItemSelectedListener"):
                                 activity.index("private ModelRecommendation.Goal selectedGoal")]
        self.assertNotIn("translateSwitch.setChecked", goal_listener)
        self.assertIn("runtime prompt", values["models_network_body"].lower())
        self.assertIn("cannot revoke", values["models_remove_network_body"].lower())
        self.assertNotIn("parakeet", values["models_recommendation_installed"].lower())
        self.assertIn('.orNull?.toBooleanStrictOrNull() ?: false', gradle)
        self.assertIn("showInitialModelSetupIfNeeded()", main)
        self.assertIn("hasUsableSpeechModel()", main)
        self.assertNotIn("model_setup_intro_seen", main)
        self.assertIn("modelSetupPromptChecked", main)
        self.assertNotIn('requestPermissions(new String[]{Manifest.permission.INTERNET}',
                         activity + main)
        self.assertIn("bestSupportedDeviceLanguage(tags)", activity)
        self.assertIn("Locale.getDefault()", activity)
        self.assertIn("memory.totalMem", activity)
        self.assertIn("getFilesDir().getUsableSpace()", activity)
        inventory = (ROOT / "app/src/main/java/dev/notune/transcribe/SpeechModelInventory.java").read_text()
        self.assertIn('new File(filesDir, "builtin-model")', inventory)
        self.assertIn("hasRetainedBuiltInModel", activity + main + inventory)

    def test_transcript_recovery_is_private_sensitive_and_off_main_thread(self):
        ime = (ROOT / "app/src/main/java/dev/notune/transcribe/RustInputMethodService.java").read_text()
        recovery = (ROOT / "app/src/main/java/dev/notune/transcribe/ImeTranscriptRecoveryActivity.java").read_text()
        clipboard = (ROOT / "app/src/main/java/dev/notune/transcribe/TranscriptClipboard.java").read_text()
        self.assertIn("RECOVERY_IO.execute", ime)
        self.assertIn("recoveryIo.execute", recovery)
        self.assertIn("ClipDescription.EXTRA_IS_SENSITIVE", clipboard)
        self.assertIn("FLAG_SECURE", recovery)
        self.assertIn("ViewCompat.setAccessibilityHeading", recovery)
        self.assertIn("ime_recovery_inserted_clear_failed", ime)
        self.assertIn('android:saveEnabled="false"',
                      (ROOT / "app/src/main/res/layout/activity_ime_transcript_recovery.xml").read_text())
        self.assertNotIn("ClipData.newPlainText", ime + recovery)
        auto_record = ime[ime.index("private void tryStartAutomaticRecording()"):
                          ime.index("@Override\n    public void onFinishInput()")]
        pending_branch = auto_record[auto_record.index("if (hasPendingTranscriptRecovery())"):
                                     auto_record.index("if (isPauseAudioEnabled())")]
        self.assertNotIn("openTranscriptRecovery()", pending_branch)
        self.assertIn("updateUiState()", pending_branch)

    def test_theme_rebuild_reapplies_keyboard_insets(self):
        ime = (ROOT / "app/src/main/java/dev/notune/transcribe/RustInputMethodService.java").read_text()
        self.assertIn("int basePaddingBottom = view.getPaddingBottom();", ime)
        self.assertIn("basePaddingBottom + insets.getSystemWindowInsetBottom()", ime)
        self.assertNotIn("originalPaddingBottom", ime)
        theme_branch = ime[ime.index("// Rebuild the keyboard if the theme preference changed"):
                           ime.index("@Override\n    public void onFinishInput")]
        self.assertIn("setInputView(refreshedView);", theme_branch)
        self.assertIn("refreshedView.requestApplyInsets();", theme_branch)

    def test_native_backend_is_optimized_with_safe_armv8_fallback(self):
        loader = (ROOT / "app/src/main/java/dev/notune/transcribe/NativeLibraries.java").read_text()
        activity = (ROOT / "app/src/main/java/dev/notune/transcribe/ModelsActivity.java").read_text()
        build = (ROOT / "app/build.gradle.kts").read_text()
        probe = (ROOT / "app/src/main/cpp/cpu_features_probe.c").read_text()
        self.assertIn('"android_transcribe_app_dotprod"', loader)
        self.assertIn('"android_transcribe_app_armv8"', loader)
        self.assertIn('"ostt_cpu_probe"', loader)
        self.assertIn("supportsOptimizedBackendNative", loader)
        self.assertNotIn("/proc/self/auxv", loader)
        self.assertIn("NativeLibraries.supportsOptimizedBackend()", activity)
        self.assertIn('nativeVariant("Armv8", "armv8-a")', build)
        self.assertIn('nativeVariant("Dotprod", "armv8.2-a+dotprod+fp16")', build)
        self.assertIn("compileCpuProbe", build)
        self.assertIn("libostt_cpu_probe.so", build)
        self.assertIn("getauxval(AT_HWCAP)", probe)
        self.assertIn("HWCAP_ASIMDHP | HWCAP_ASIMDDP", probe)
        for source in (
                "MainActivity.java", "ModelsActivity.java", "RustInputMethodService.java",
                "ForegroundRecordingService.java", "RecognizeActivity.java",
                "LiveSubtitleService.java", "TranscribeFileActivity.java",
                "VoiceRecognitionService.java"):
            text = (ROOT / "app/src/main/java/dev/notune/transcribe" / source).read_text()
            self.assertIn("NativeLibraries.load()", text)

    def test_voice_panel_position_is_optional_and_shared_by_settings_and_popup(self):
        main = (ROOT / "app/src/main/java/dev/notune/transcribe/MainActivity.java").read_text()
        popup = (ROOT / "app/src/main/java/dev/notune/transcribe/RecognizeActivity.java").read_text()
        layout = (ROOT / "app/src/main/res/layout/activity_main.xml").read_text()
        side = (ROOT / "app/src/main/res/layout/recognize_activity_side.xml").read_text()
        self.assertIn("setupRecognizePanelPosition()", main)
        self.assertIn("RecognizePanelPrefs.get(this)", popup)
        self.assertIn("RecognizePanelPrefs.BOTTOM", popup)
        self.assertIn('@+id/spinner_recognize_panel_position', layout)
        for layout_name in ("recognize_activity.xml", "recognize_activity_side.xml"):
            candidate = (ROOT / "app/src/main/res/layout" / layout_name).read_text()
            for view_id in set(re.findall(r"R\.id\.([A-Za-z0-9_]+)", popup)):
                self.assertIn(f'@+id/{view_id}', candidate,
                              f"{layout_name} supplies every view RecognizeActivity binds")
        self.assertIn('layout_gravity="end|center_vertical"', side)
        self.assertIn("Gravity.START", popup)

    def test_models_screen_exposes_active_details_and_retry(self):
        layout = (ROOT / "app/src/main/res/layout/activity_models.xml").read_text()
        source = (ROOT / "app/src/main/java/dev/notune/transcribe/ModelsActivity.java").read_text()
        self.assertIn('@+id/text_active_model_summary', layout)
        self.assertIn('@+id/btn_model_retry', layout)
        base_strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        copy = {item.attrib["name"]: "".join(item.itertext())
                for item in base_strings.findall("string")}
        self.assertEqual("Installed models", copy["models_active_section"])
        self.assertEqual("Manual options", copy["models_manual_section"])
        tree = ET.parse(ROOT / "app/src/main/res/layout/activity_models.xml")
        android = "{http://schemas.android.com/apk/res/android}"
        by_id = {view.attrib.get(android + "id"): view for view in tree.iter()}
        card_order = [view.attrib.get(android + "id") for view in tree.iter()
                      if view.tag.endswith("MaterialCardView")]
        expected_cards = [
            "@+id/card_installed_models",
            "@+id/card_custom_terms",
            "@+id/card_recommendation",
            "@+id/card_model_guide",
            "@+id/card_download_catalog",
            "@+id/card_manual_models",
        ]
        self.assertEqual(expected_cards, card_order[:len(expected_cards)])
        self.assertEqual("gone", by_id["@+id/txt_model_status"].attrib[android + "visibility"])
        self.assertEqual("gone", by_id["@+id/btn_model_retry"].attrib[android + "visibility"])
        self.assertIn("private void showModelStatus", source)
        self.assertIn('retryButton.setVisibility(failed ? View.VISIBLE : View.GONE)', source)
        self.assertIn("models_provenance_imported", source)
        self.assertIn("DefaultModelContract.BYTE_SIZE", source)
        self.assertIn("reloadModelNative(this)", source)

    def test_custom_terms_are_bounded_private_and_model_gated(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        layout = (ROOT / "app/src/main/res/layout/activity_models.xml").read_text()
        editor_layout = (ROOT / "app/src/main/res/layout/activity_custom_terms.xml").read_text()
        correction_row = (ROOT / "app/src/main/res/layout/item_personal_correction.xml").read_text()
        store = (ROOT / "app/src/main/java/dev/notune/transcribe/CustomTerms.java").read_text()
        activity = (ROOT / "app/src/main/java/dev/notune/transcribe/CustomTermsActivity.java").read_text()
        catalog = (ROOT / "app/src/main/java/dev/notune/transcribe/ModelCatalog.java").read_text()
        engine = (ROOT / "src/engine.rs").read_text()
        native_terms = (ROOT / "src/custom_terms.rs").read_text()
        corrections = (ROOT / "app/src/main/java/dev/notune/transcribe/PersonalCorrections.java").read_text()
        native_corrections = (ROOT / "src/personal_corrections.rs").read_text()
        self.assertIn('android:name=".CustomTermsActivity"', manifest)
        self.assertIn('android:exported="false"', manifest)
        self.assertIn('@+id/card_custom_terms', layout)
        self.assertIn('@+id/custom_terms_editor', editor_layout)
        self.assertIn('@+id/personal_corrections_toggle', editor_layout)
        self.assertIn('@+id/personal_corrections_content', editor_layout)
        self.assertIn('setCorrectionsVisible(hasSavedRules || correctionsEnabledSwitch.isChecked())', activity)
        self.assertIn('whisperPromptActive', activity)
        self.assertIn('R.string.custom_terms_count_whisper', activity)
        self.assertIn('android:scrollbars="none"', editor_layout)
        self.assertIn('android:minHeight="144dp"', editor_layout)
        self.assertIn('android:minLines="5"', editor_layout)
        self.assertIn('Whisper uses the first terms as hints; other models',
                      (ROOT / "app/src/main/res/values/strings.xml").read_text())
        self.assertIn("MAX_TERMS = 128", store)
        self.assertIn("MAX_PROMPT_UTF8_BYTES = 200", store)
        self.assertIn("StandardCopyOption.ATOMIC_MOVE", store)
        self.assertIn("Normalizer.Form.NFC", store)
        self.assertNotIn("DiagnosticLog", store + activity)
        self.assertIn("VerifiedModelProvenance.isVerified", activity)
        self.assertIn('? ModelDescriptor.Capability.SUPPORTED', catalog)
        self.assertIn('model.supports(transcribe_cpp::Feature::InitialPrompt)', engine)
        prompt_snapshot = engine.index("let prompt = if self.supports_initial_prompt")
        self.assertLess(prompt_snapshot,
                        engine.index("TranscriptionJob {", prompt_snapshot))
        self.assertIn("let mut job = self.begin_transcription()", engine)
        self.assertIn("self.transcribe_into(&mut job, &samples)?", engine)
        self.assertIn("custom-term list", native_terms)
        self.assertNotIn("log::", native_terms)
        self.assertIn('@+id/personal_corrections_enabled', editor_layout)
        self.assertIn('@+id/personal_corrections_rows', editor_layout)
        self.assertIn('@+id/personal_corrections_add', editor_layout)
        self.assertNotIn('personal_corrections_editor', editor_layout)
        self.assertIn('@+id/personal_correction_original', correction_row)
        self.assertIn('@+id/personal_correction_replacement', correction_row)
        self.assertIn('@string/personal_corrections_original_hint', correction_row)
        self.assertIn('@string/personal_corrections_replacement_hint', correction_row)
        self.assertIn('collectCorrectionPairs()', activity)
        self.assertIn('PersonalCorrections.writePairs(', activity)
        base_strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        copy = {item.attrib["name"]: "".join(item.itertext())
                for item in base_strings.findall("string")}
        self.assertNotIn("=>", copy["personal_corrections_desc"])
        self.assertIn('MAX_RULES = 32', corrections)
        self.assertIn('static Parsed fromPairs', corrections)
        self.assertIn('StandardCopyOption.ATOMIC_MOVE', corrections)
        self.assertIn('NUMBER_MISMATCH', corrections)
        self.assertNotIn('DiagnosticLog', corrections)
        self.assertIn('personal_corrections::load(&self.files_dir)', engine)
        self.assertIn('personal_corrections::apply_with_ranges(&self.text, rules)', engine)
        self.assertIn('preferred_terms: if self.supports_initial_prompt', engine)
        self.assertIn('preferred_terms::apply_with_protected(&text, terms, &protected)', engine)
        self.assertLess(engine.index('personal_corrections::load(&self.files_dir)'),
                        engine.index('pub(crate) fn transcribe_into'))
        self.assertIn('is_protected_token', native_corrections)
        self.assertIn('inside_double_quote', native_corrections)
        self.assertNotIn('log::', native_corrections)

    def test_settings_schema_migration_is_applied_before_theme(self):
        app = (ROOT / "app/src/main/java/dev/notune/transcribe/App.java").read_text()
        self.assertLess(app.index("SettingsSchema.migrate"), app.index("ThemePrefs.apply"))
        schema = (ROOT / "app/src/main/java/dev/notune/transcribe/SettingsSchema.java").read_text()
        self.assertIn("FileLock", schema)
        self.assertIn("StandardCopyOption.ATOMIC_MOVE", schema)
        self.assertNotIn("modelsDir", schema)


if __name__ == "__main__":
    unittest.main()
