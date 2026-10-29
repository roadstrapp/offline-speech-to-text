#!/bin/sh
set -eu
export PYTHONDONTWRITEBYTECODE=1
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
PYTHON=${PYTHON_BIN:-python3}

(
  cd "$ROOT"
  "$PYTHON" -m unittest \
    tests.test_release_configuration \
    tests.test_documentation_contract -v
)
javac -d "$OUT" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/HomeNavigation.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelPresentation.java" \
  "$ROOT/tests/HomeNavigationTest.java" \
  "$ROOT/tests/ModelPresentationTest.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/AutoStopDelay.java" \
  "$ROOT/tests/AutoStopDelayTest.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/DictionaryFiles.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/DictionaryText.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/CustomTerms.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/PersonalCorrections.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/StoredThemeMode.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ImportedModelSelection.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/DefaultModelContract.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/SpeechModelInventory.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelDescriptor.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelCatalog.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelRecommendation.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelGuidance.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelDownloadPolicy.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelDownloadLifecycle.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelDownloadTask.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelImportTask.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelImportLifecycle.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/VerifiedModelProvenance.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelInstaller.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/SetupState.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ImeTextEdit.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ImeRecordingSession.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/PendingTranscriptRecord.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/PendingTranscriptFiles.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/EmergencyTranscriptBuffer.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/SettingsSchema.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/DiagnosticLog.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/NativeStatus.java" \
  "$ROOT/tests/BehaviorTest.java" \
  "$ROOT/tests/TroubleshootingLeaseTest.java" \
  "$ROOT/tests/DiagnosticRegressionTest.java" \
  "$ROOT/tests/ModelDownloadBoundaryTest.java" \
  "$ROOT/tests/ModelImportTaskTest.java" \
  "$ROOT/tests/CustomTermsStoreTest.java" \
  "$ROOT/tests/PersonalCorrectionsStoreTest.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/SpokenLanguages.java" \
  "$ROOT/tests/SpokenLanguagesTest.java" \
  "$ROOT/tests/PendingTranscriptRecordTest.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/FileTranscriptionContract.java" \
  "$ROOT/tests/FileTranscriptionContractTest.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/BluetoothMicPolicy.java" \
  "$ROOT/tests/BluetoothMicPolicyTest.java"
java -ea -cp "$OUT" dev.notune.transcribe.HomeNavigationTest
java -ea -cp "$OUT" dev.notune.transcribe.ModelPresentationTest
java -ea -cp "$OUT" dev.notune.transcribe.BehaviorTest
java -ea -cp "$OUT" dev.notune.transcribe.TroubleshootingLeaseTest
java -ea -cp "$OUT" dev.notune.transcribe.DiagnosticRegressionTest
java -ea -cp "$OUT" dev.notune.transcribe.AutoStopDelayTest
java -ea -cp "$OUT" dev.notune.transcribe.ModelDownloadBoundaryTest
java -ea -cp "$OUT" dev.notune.transcribe.ModelImportTaskTest
java -ea -cp "$OUT" dev.notune.transcribe.CustomTermsStoreTest
java -ea -cp "$OUT" dev.notune.transcribe.PersonalCorrectionsStoreTest
java -ea -cp "$OUT" dev.notune.transcribe.PendingTranscriptRecordTest
java -ea -cp "$OUT" dev.notune.transcribe.SpokenLanguagesTest
java -ea -cp "$OUT" dev.notune.transcribe.FileTranscriptionContractTest
java -ea -cp "$OUT" dev.notune.transcribe.BluetoothMicPolicyTest

javac -d "$OUT" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ForegroundRecordingCoordinator.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ForegroundServiceStopPolicy.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ForegroundTypePolicy.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/MicrophonePermissionPolicy.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/NotificationContract.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/TransientResultVault.java" \
  "$ROOT/tests/M4LifecycleTest.java"
java -ea -cp "$OUT" dev.notune.transcribe.M4LifecycleTest

rustc --edition 2021 --test "$ROOT/tests/BackgroundChunkingTest.rs" \
  -o "$OUT/background_chunking_test"
"$OUT/background_chunking_test"

rustc --edition 2021 --test "$ROOT/tests/BackgroundWorkerTest.rs" \
  -o "$OUT/background_worker_test"
"$OUT/background_worker_test"

rustc --edition 2021 --test "$ROOT/tests/InteractiveEndpointingTest.rs" \
  -o "$OUT/interactive_endpointing_test"
"$OUT/interactive_endpointing_test"

rustc --edition 2021 --test "$ROOT/tests/LongAudioTest.rs" \
  -o "$OUT/long_audio_test"
"$OUT/long_audio_test"

rustc --edition 2021 --test "$ROOT/src/audio_finalization.rs" \
  -o "$OUT/audio_finalization_test"
"$OUT/audio_finalization_test"

rustc --edition 2021 --test "$ROOT/tests/EnglishFillersTest.rs" \
  -o "$OUT/english_fillers_test"
"$OUT/english_fillers_test"

rustc --edition 2021 --test "$ROOT/src/language_selection.rs" \
  -o "$OUT/language_selection_test"
"$OUT/language_selection_test"

rustc --edition 2021 --test "$ROOT/src/bounded_preroll.rs" \
  -o "$OUT/bounded_preroll_test"
"$OUT/bounded_preroll_test"

rustc --edition 2021 --test "$ROOT/tests/CustomTermsTest.rs" \
  -o "$OUT/custom_terms_test"
"$OUT/custom_terms_test"

rustc --edition 2021 --test "$ROOT/tests/PreferredTermsTest.rs" \
  -o "$OUT/preferred_terms_test"
"$OUT/preferred_terms_test"

rustc --edition 2021 --test "$ROOT/tests/PersonalCorrectionsTest.rs" \
  -o "$OUT/personal_corrections_test"
"$OUT/personal_corrections_test"

javac -d "$OUT" \
  "$ROOT/docs/examples/CallerAudioSessionCore.java" \
  "$ROOT/tests/CallerAudioLifecycleTest.java"
java -ea -cp "$OUT" docs.examples.CallerAudioLifecycleTest

rustc --edition 2021 --test "$ROOT/src/caller_audio_input.rs" -o "$OUT/caller_audio_input_test"
"$OUT/caller_audio_input_test"

rustc --edition 2021 --test "$ROOT/tests/CallerAudioJobTest.rs" -o "$OUT/caller_audio_job_test"
"$OUT/caller_audio_job_test"

javac -d "$OUT" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/RecognitionSessionPolicy.java" \
  "$ROOT/tests/RecognitionSessionPolicyTest.java"
java -ea -cp "$OUT" dev.notune.transcribe.RecognitionSessionPolicyTest
rustc --edition 2021 --test "$ROOT/src/capture_health.rs" -o "$OUT/capture_health_test"
"$OUT/capture_health_test"

rustc --edition 2021 --test "$ROOT/tests/UpstreamEndpointTest.rs" -o "$OUT/upstream_endpoint_test"
"$OUT/upstream_endpoint_test"
