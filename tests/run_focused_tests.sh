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
  "$ROOT/app/src/main/java/dev/notune/transcribe/StoredThemeMode.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ImportedModelSelection.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/DefaultModelContract.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelDescriptor.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelCatalog.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelRecommendation.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelGuidance.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelDownloadPolicy.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelDownloadLifecycle.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelDownloadTask.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/VerifiedModelProvenance.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ModelInstaller.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/SetupState.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ImeTextEdit.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ImeRecordingSession.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/SettingsSchema.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/DiagnosticLog.java" \
  "$ROOT/tests/BehaviorTest.java" \
  "$ROOT/tests/ModelDownloadBoundaryTest.java"
java -ea -cp "$OUT" dev.notune.transcribe.BehaviorTest
java -ea -cp "$OUT" dev.notune.transcribe.ModelDownloadBoundaryTest

javac -d "$OUT" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ForegroundRecordingCoordinator.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ForegroundServiceStopPolicy.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/ForegroundTypePolicy.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/MicrophonePermissionPolicy.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/NotificationContract.java" \
  "$ROOT/app/src/main/java/dev/notune/transcribe/TransientResultVault.java" \
  "$ROOT/tests/M4LifecycleTest.java"
java -ea -cp "$OUT" dev.notune.transcribe.M4LifecycleTest

rustc --edition 2021 --test "$ROOT/tests/LongAudioChunkingTest.rs" \
  -o "$OUT/long_audio_chunking_test"
"$OUT/long_audio_chunking_test"

javac -d "$OUT" \
  "$ROOT/docs/examples/CallerAudioSessionCore.java" \
  "$ROOT/tests/CallerAudioLifecycleTest.java"
java -ea -cp "$OUT" docs.examples.CallerAudioLifecycleTest
