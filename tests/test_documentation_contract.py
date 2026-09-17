import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
README = ROOT / "README.md"
GUIDE = ROOT / "docs/android-integration.md"
SERVICE = ROOT / "app/src/main/java/dev/notune/transcribe/VoiceRecognitionService.java"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
M4_SERVICE = ROOT / "app/src/main/java/dev/notune/transcribe/ForegroundRecordingService.java"
M4_ACTIVITY = ROOT / "app/src/main/java/dev/notune/transcribe/ForegroundRecordingActivity.java"
M4_RECEIVER = ROOT / "app/src/main/java/dev/notune/transcribe/ForegroundRecordingActionReceiver.java"
M4_NOTIFICATIONS = ROOT / "app/src/main/java/dev/notune/transcribe/NotificationContract.java"


class DocumentationContractTest(unittest.TestCase):
    def test_local_markdown_links_resolve(self):
        for document in (README, GUIDE):
            text = document.read_text()
            self.assertEqual(0, text.count("```") % 2, f"unclosed code fence in {document}")
            for target in re.findall(r"!?\[[^]]*]\(([^)]+)\)", text):
                if target.startswith(("http://", "https://", "mailto:", "#")):
                    continue
                local_target = target.split("#", 1)[0]
                self.assertTrue((document.parent / local_target).exists(),
                                f"broken link {target} in {document}")

    def test_readme_is_user_first_and_complete(self):
        text = README.read_text()
        self.assertLessEqual(len(text.splitlines()), 100)
        for expected in (
                "## Features", "## Screenshots", "## Set up", "## Compatibility and fallback",
                "## Privacy", "## Build and contribute", "## License", "Live Subtitles",
                "voice keyboard", "audio file", "benchmark", "diagnostic", "HeliBoard",
                "ACTION_RECOGNIZE_SPEECH", "EXTRA_AUDIO_SOURCE"):
            self.assertIn(expected.lower(), text.lower())
        self.assertNotIn("Heliboard", text)
        self.assertNotIn("android_transcribe_app", text)
        self.assertNotIn("485,425,504", text)

    def test_guide_separates_all_android_surfaces_and_lifecycle(self):
        text = GUIDE.read_text()
        for expected in (
                "Provider-owned recognition UI", "App UI with provider microphone capture",
                "Custom UI with caller-supplied audio", "Voice IME contract",
                "RecognitionService.SERVICE_INTERFACE", "onReadyForSpeech",
                "onBeginningOfSpeech", "onRmsChanged", "onEndOfSpeech", "onResults",
                "onError", "stopListening", "cancel", "destroy", "exact-origin",
                'foregroundServiceType=\"microphone\"', "FOREGROUND_SERVICE_MICROPHONE",
                "getCallingAttributionSource", "does not emit partial-result callbacks"):
            self.assertIn(expected, text)

    def test_long_recording_docs_define_user_controls_and_limits(self):
        readme = README.read_text()
        guide = GUIDE.read_text()
        for expected in (
                "Long recording", "Start recording", "Stop and discard",
                "Stop and transcribe", "Transcribing — microphone off",
                "Share and clear result", "ten minutes", "notification permission"):
            self.assertIn(expected.lower(), (readme + guide).lower())
        for expected in (
                "does not automatically insert", "does not survive process death",
                "microphone privacy", "audio focus", "force-stop"):
            self.assertIn(expected.lower(), guide.lower())

    def test_long_recording_runtime_matches_fail_closed_contract(self):
        service = M4_SERVICE.read_text()
        activity = M4_ACTIVITY.read_text()
        receiver = M4_RECEIVER.read_text()
        forms = M4_NOTIFICATIONS.read_text()
        manifest = MANIFEST.read_text()

        for exact in (
                '"Preparing recording"', '"Microphone off • cancel to stop setup"',
                '"Recording speech"', '"Microphone on • tap an action to finish"',
                '"Transcribing — microphone off"', '"Processing on device"'):
            self.assertIn(exact, forms)
        self.assertIn('android:foregroundServiceType="microphone|shortService"', manifest)
        self.assertNotIn("mediaProcessing", manifest)
        self.assertNotIn("FOREGROUND_SERVICE_MEDIA_PROCESSING", manifest)

        for expected in (
                "PermissionChecker.checkSelfPermission", "FOREGROUND_SERVICE_IMMEDIATE",
                "NOTIFICATION_POLL_INTERVAL_MS = 500L", "CAPTURE_TIMEOUT_MS = 10 * 60_000L",
                "INFERENCE_TIMEOUT_MS = 150_000L", "ServiceCompat.startForeground",
                "ServiceCompat.stopForeground", "confirmActiveNotificationAbsentThen",
                "Binder.getCallingUid() == Process.myUid()", "setUsesChronometer(true)"):
            self.assertIn(expected, service)
        self.assertIn("ForegroundTypePolicy.captureType", service)
        self.assertIn("ForegroundTypePolicy.inferenceType", service)
        self.assertIn("NotificationContract.labelFor(action)", service)
        self.assertIn("b.setWhen(session.startedAtMillis).setUsesChronometer(true)", service)
        self.assertIn("cancelAndFinish(generation", service)
        self.assertIn("if (!discard(intent)) stopIfNoActiveSession(startId)", service)
        self.assertIn("if (!stopForTranscription(intent)) stopIfNoActiveSession(startId)", service)
        on_start = self._method_body(service, "onStartCommand(Intent intent, int flags, int startId)")
        self.assertNotIn("stopSelfResult(startId)", on_start)
        self.assertGreaterEqual(on_start.count("stopIfNoActiveSession(startId)"), 5)
        cleanup = self._method_body(service, "cancelAndFinish(long generation, String threadName)")
        self.assertLess(cleanup.index("cancelRecording()"), cleanup.index("finishDiscard(generation)"))
        capture_end = self._method_body(
            service, "captureEnded(ForegroundRecordingCoordinator.Session session)")
        self.assertLess(capture_end.index("releaseFocus()"), capture_end.index("beginTranscription()"))
        removal_check = self._method_body(
            service,
            "confirmActiveNotificationAbsentThen(\n            ForegroundRecordingCoordinator.Session session, Runnable confirmed)")
        self.assertIn("ResultNotification.canPost", removal_check)
        self.assertIn("notificationMatches(RESULT_NOTIFICATION_ID", removal_check)
        self.assertNotIn("up to 10 minutes", activity)
        self.assertIn("PermissionChecker.checkSelfPermission", activity)
        self.assertIn("Manifest.permission.POST_NOTIFICATIONS", activity)
        self.assertIn("ForegroundRecordingService.ensureChannel(this)", activity)
        self.assertIn('"Review result"', activity)
        self.assertIn('"Discard result"', activity)
        result_activity = (ROOT / "app/src/main/java/dev/notune/transcribe/ForegroundResultActivity.java").read_text()
        self.assertIn("isStaged(token, generation, nonce)", result_activity)
        self.assertIn("ResultNotification.post(this, pending)", result_activity)
        self.assertIn("ResultNotification.canPost(this)", result_activity)
        self.assertTrue((ROOT / "app/src/main/java/dev/notune/transcribe/ResultExpiryScheduler.java").exists())
        self.assertIn("dispatchActiveAction", receiver)
        self.assertNotIn("startService(", receiver)

        ime = (ROOT / "app/src/main/java/dev/notune/transcribe/RustInputMethodService.java").read_text()
        self.assertIn("IForegroundRecordingObserver.Stub", ime)
        self.assertIn("bindService(", ime)
        self.assertIn("new Intent(this, ForegroundRecordingService.class)", ime)
        for callback in ("public void onWindowHidden()", "public void onFinishInput()",
                         "public void onFinishInputView(boolean finishingInput)",
                         "public void onDestroy()"):
            body = self._method_body(ime, callback)
            self.assertIn("unbindLongRecordingState()", body)

        transaction = service[service.index("private void commitResult"):
                              service.index("public void onAudioLevel")]
        self.assertLess(transaction.index(".stage("), transaction.index("postResult("))
        self.assertLess(transaction.index("postResult("),
                        transaction.index("confirmActiveNotificationAbsentThen"))
        self.assertLess(transaction.index("confirmActiveNotificationAbsentThen"),
                        transaction.index(".commit("))

        task_removed = self._method_body(service, "onTaskRemoved(Intent rootIntent)")
        self.assertNotIn("discard(", task_removed)
        self.assertNotIn("cancelRecording(", task_removed)

    def test_capture_buffers_move_into_inference_without_full_copy(self):
        voice = (ROOT / "src/voice_session.rs").read_text()
        recognizer = (ROOT / "src/recog_service.rs").read_text()
        self.assertIn("std::mem::take(&mut *state.audio_buffer.lock().unwrap())", voice)
        self.assertIn("std::mem::take(&mut *shared.audio_buffer.lock().unwrap())", recognizer)
        self.assertIn("health_last_nonzero", voice)
        self.assertNotIn("health_nonzero = Arc::new(AtomicBool", voice)
        for owner in ("OWNER_IME", "OWNER_ACTIVITY", "OWNER_FOREGROUND",
                      "OWNER_RECOGNITION_SERVICE"):
            self.assertIn(owner, voice + recognizer)

    def test_caller_audio_format_and_callbacks_match_service_source(self):
        guide = GUIDE.read_text()
        service = SERVICE.read_text()
        for constant in (
                "EXTRA_AUDIO_SOURCE", "EXTRA_AUDIO_SOURCE_SAMPLING_RATE",
                "EXTRA_AUDIO_SOURCE_CHANNEL_COUNT", "EXTRA_AUDIO_SOURCE_ENCODING"):
            self.assertIn(constant, guide)
            self.assertIn(constant, service)
        for callback in (
                "readyForSpeech", "beginningOfSpeech", "rmsChanged", "endOfSpeech",
                "cb.results", "cb.error"):
            self.assertIn(callback, service)
        self.assertNotIn("partialResults", service)
        self.assertIn("16,000 Hz", guide)
        self.assertIn("PCM 16-bit", guide)
        self.assertIn("OsConstants.O_NONBLOCK", guide)

    def test_caller_audio_sample_links_executable_lifecycle_contract(self):
        sample = self._caller_audio_sample()
        guide = GUIDE.read_text()
        core = ROOT / "docs/examples/CallerAudioSessionCore.java"
        lifecycle_test = ROOT / "tests/CallerAudioLifecycleTest.java"
        self.assertIn("examples/CallerAudioSessionCore.java", guide)
        self.assertTrue(core.exists())
        self.assertTrue(lifecycle_test.exists())
        for state in ("STREAMING", "WAITING_RESULT", "FAILURE_PENDING", "CANCELLED", "DESTROYED"):
            self.assertIn(state, sample)

    @staticmethod
    def _caller_audio_sample():
        guide = GUIDE.read_text()
        marker = "class CallerAudioSpeechSession("
        start = guide.index(marker)
        return guide[start:guide.index("```", start)]

    @staticmethod
    def _method_body(sample, signature):
        try:
            start = sample.index(signature)
        except ValueError as error:
            raise AssertionError(f"missing method: {signature}") from error
        brace = sample.index("{", start)
        depth = 0
        for index in range(brace, len(sample)):
            if sample[index] == "{":
                depth += 1
            elif sample[index] == "}":
                depth -= 1
                if depth == 0:
                    return sample[brace + 1:index]
        raise AssertionError(f"unclosed method: {signature}")

    def test_manifest_supports_documented_entry_points_and_privacy_claim(self):
        readme = README.read_text()
        manifest = MANIFEST.read_text()
        self.assertIn("android.speech.action.RECOGNIZE_SPEECH", manifest)
        self.assertIn("android.speech.RecognitionService", manifest)
        self.assertIn("android.view.InputMethod", manifest)
        self.assertIn("android.permission.INTERNET", manifest)
        self.assertIn("makes no network request on launch", readme)


if __name__ == "__main__":
    unittest.main()
