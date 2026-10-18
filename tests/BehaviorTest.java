package dev.notune.transcribe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public final class BehaviorTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "diagnostic-writer".equals(args[0])) {
            Path filesDir = Path.of(args[1]);
            for (int index = 0; index < Integer.parseInt(args[2]); index++) {
                DiagnosticLog.event(filesDir.toFile(), "timing", "elapsed_ms=" + index);
            }
            if (!DiagnosticLog.awaitIdleForTest(10_000)) System.exit(2);
            return;
        }
        themeDefaultsToDarkButPreservesExplicitChoices();
        successfulImportBecomesActiveModel();
        exactDefaultModelContractAcceptsOnlyThePinnedArtifact();
        compactUpdatesRetainExistingModelsWithoutBundling();
        nativeStatusClassificationIsSharedAndStable();
        modelCatalogIsCapabilityHonest();
        recommendationFiltersBeforeRanking();
        modelGuidanceUsesConservativeSizeAndDeviceTiers();
        downloadPolicyIsStrictAboutResumeAndIntegrity();
        configurationChangesKeepDownloadOwnershipAlive();
        downloadTrustBoundaryRejectsUntrustedHostsAndDiscardsCancelledStaging();
        importedModelNamesStayInsidePrivateModelStorage();
        verifiedInstallerRechecksStagedBytesAndDestinationPath();
        verifiedModelLabelsRequirePersistedProvenance();
        setupStatesAreExplicit();
        imeEditorSafetyIsFailClosed();
        recordingSessionTracksTheSafeEditorLifecycleNotWrapperIdentity();
        deletePreviewMovesByWholeGraphemeClusters();
        deletePreviewExtendsAnExistingSelectionByCluster();
        settingsMigrationPreservesUnknownAndFutureState();
        diagnosticLoggingIsFailClosedAndBounded();
    }

    private static void themeDefaultsToDarkButPreservesExplicitChoices() {
        assertEquals(2, StoredThemeMode.resolve(null), "missing theme defaults to dark");
        assertEquals(2, StoredThemeMode.resolve(""), "empty theme defaults to dark");
        assertEquals(2, StoredThemeMode.resolve("garbage"), "invalid theme defaults to dark");
        assertEquals(-1, StoredThemeMode.resolve("-1"), "explicit system choice is preserved");
        assertEquals(1, StoredThemeMode.resolve("1"), "explicit light choice is preserved");
        assertEquals(2, StoredThemeMode.resolve("2"), "explicit dark choice is preserved");
    }

    private static void successfulImportBecomesActiveModel() throws Exception {
        Path filesDir = Files.createTempDirectory("offline-stt-model-test");
        ImportedModelSelection.activate(filesDir.toFile(), "custom.gguf");
        String active = Files.readString(filesDir.resolve("active_model"), StandardCharsets.UTF_8);
        assertEquals("custom.gguf", active, "successful import selects the imported model");
        assertThrows(IllegalArgumentException.class,
                () -> ImportedModelSelection.activate(filesDir.toFile(), "../outside.gguf"),
                "active model selection rejects names outside private model storage");
        assertThrows(IllegalArgumentException.class,
                () -> ImportedModelSelection.activate(filesDir.toFile(), "not-a-model.txt"),
                "active model selection accepts only model filenames");
    }

    private static void exactDefaultModelContractAcceptsOnlyThePinnedArtifact() {
        assertEquals(true, DefaultModelContract.matches(
                        DefaultModelContract.FILE_NAME,
                        DefaultModelContract.BYTE_SIZE,
                        DefaultModelContract.SHA256),
                "the exact default model is accepted");
        assertEquals(false, DefaultModelContract.matches(
                        DefaultModelContract.FILE_NAME,
                        DefaultModelContract.BYTE_SIZE - 1,
                        DefaultModelContract.SHA256),
                "a size mismatch is rejected");
        assertEquals(false, DefaultModelContract.matches(
                        DefaultModelContract.FILE_NAME,
                        DefaultModelContract.BYTE_SIZE,
                        "0000000000000000000000000000000000000000000000000000000000000000"),
                "a digest mismatch is rejected");
    }

    private static void compactUpdatesRetainExistingModelsWithoutBundling() throws Exception {
        Path fresh = Files.createTempDirectory("offline-stt-fresh-compact");
        assertEquals(false, SpeechModelInventory.hasUsableModel(fresh.toFile(), false),
                "a fresh compact install needs model setup");

        Path retainedDirectory = fresh.resolve("builtin-model");
        Files.createDirectories(retainedDirectory);
        Path retained = retainedDirectory.resolve(DefaultModelContract.FILE_NAME);
        try (java.io.RandomAccessFile model = new java.io.RandomAccessFile(
                retained.toFile(), "rw")) {
            model.setLength(DefaultModelContract.BYTE_SIZE);
        }
        Files.writeString(retainedDirectory.resolve(".extraction_complete"), "ok");
        assertEquals(true, SpeechModelInventory.hasUsableModel(fresh.toFile(), false),
                "a compact update keeps the previously extracted model usable");

        Path selected = Files.createTempDirectory("offline-stt-selected-compact");
        Files.createDirectories(selected.resolve("models"));
        Files.writeString(selected.resolve("models/custom.gguf"), "model");
        Files.writeString(selected.resolve("active_model"), "custom.gguf");
        assertEquals(true, SpeechModelInventory.hasUsableModel(selected.toFile(), false),
                "a downloaded model survives ordinary compact APK updates");
        Files.writeString(selected.resolve("active_model"), "../escape.gguf");
        assertEquals(false, SpeechModelInventory.hasUsableModel(selected.toFile(), false),
                "setup inventory rejects active-model path traversal");
    }

    private static void modelCatalogIsCapabilityHonest() {
        ModelDescriptor model = ModelCatalog.verifiedDefault();
        assertEquals(DefaultModelContract.FILE_NAME, model.fileName,
                "catalog and exact artifact contract share one filename");
        assertEquals(DefaultModelContract.SHA256, model.sha256,
                "catalog and exact artifact contract share one digest");
        assertEquals(true, model.supportsLanguage("de-DE"),
                "the verified Parakeet language set accepts regional tags");
        assertEquals(false, model.supportsLanguage("zh-CN"),
                "the catalog does not invent language coverage");
        assertEquals(ModelDescriptor.Capability.SUPPORTED, model.languageDetection,
                "automatic language detection is explicit");
        assertEquals(ModelDescriptor.Capability.UNSUPPORTED, model.translation,
                "translation is not advertised for Parakeet");
        assertEquals(ModelDescriptor.Capability.UNSUPPORTED, model.prompt,
                "the pinned Parakeet runtime does not advertise prompt support");
        assertEquals(true, model.abis.contains("arm64-v8a")
                        && !model.requiresDotProduct && !model.requiresFp16,
                "the baseline backend keeps every arm64 model installable");
        assertEquals(53, ModelCatalog.verifiedDownloads().size(),
                "every model variant supported by the pinned runtime is downloadable");
        assertEquals(true, ModelCatalog.verifiedDownloads().stream().allMatch(
                        ModelDescriptor::isVerifiedDownload),
                "every catalog download has an exact size, digest, and HTTPS source");
        Set<String> modelIds = new HashSet<>();
        Set<String> modelFiles = new HashSet<>();
        for (ModelDescriptor candidate : ModelCatalog.verifiedDownloads()) {
            assertEquals(true, modelIds.add(candidate.id), "catalog model IDs are unique");
            assertEquals(true, modelFiles.add(candidate.fileName),
                    "catalog model filenames are unique");
        }
        assertEquals(true, ModelCatalog.verifiedDownloads().stream().anyMatch(
                        candidate -> candidate.architecture.equals("Whisper")
                                && candidate.fileName.contains("large-v3")),
                "the catalog includes large Whisper variants");
        ModelDescriptor whisper = ModelCatalog.findByFileName("whisper-small-Q4_K_M.gguf");
        assertEquals(ModelDescriptor.Capability.SUPPORTED, whisper.prompt,
                "the pinned Whisper runtime advertises initial-prompt support");
        assertEquals(true, whisper.supportsLanguage("ta-IN"),
                "multilingual Whisper guidance includes its broad language set");
        assertEquals(false, whisper.supportsLanguage("yue-HK"),
                "pre-v3 Whisper is not advertised for Cantonese");
        ModelDescriptor turbo = ModelCatalog.findByFileName(
                "whisper-large-v3-turbo-Q4_K_M.gguf");
        assertEquals(true, turbo.supportsLanguage("yue-HK"),
                "Whisper v3 guidance includes Cantonese");
        assertEquals(ModelDescriptor.Capability.UNSUPPORTED, turbo.translation,
                "Whisper Turbo is not advertised as a translating model");
        assertEquals(true, ModelCatalog.verifiedDownloads().stream().anyMatch(
                        candidate -> candidate.architecture.equals("Parakeet")
                                && candidate.fileName.contains("1.1b")),
                "the catalog includes larger Parakeet variants");
        ModelDescriptor primeLine = ModelCatalog.localePreferredBalanced("de-DE");
        assertEquals("8b01f1893fa1db166d8b08375f8300c140fc7f110af7e9b68480a27b145dad7d",
                primeLine.sha256, "primeLine is pinned to the verified artifact digest");
        assertEquals(true, primeLine.downloadUrl.contains(
                        "resolve/7c63545b352e5d410ccc1ea1eb49aa751e65780b/"),
                "primeLine downloads use the reviewed immutable model revision");
        assertEquals(true, primeLine.supportsLanguage("de-DE"),
                "primeLine advertises its retained Parakeet v3 language coverage");
        assertEquals("transcribe.cpp 0.4.0", primeLine.backend,
                "new catalog entries name the active runtime version");
        assertEquals(true, ModelCatalog.localePreferredBalanced("fr-FR") == null,
                "a language-tuned model does not override the general default elsewhere");
        assertEquals(false, ModelCatalog.isGeneralFamilyChoice(primeLine),
                "a language-tuned model is excluded from generic family highlights");
    }

    private static void nativeStatusClassificationIsSharedAndStable() {
        assertEquals(NativeStatus.Phase.ERROR, NativeStatus.classify("Error loading model"),
                "native errors have one shared classification");
        assertEquals(NativeStatus.Phase.LOADING, NativeStatus.classify("Initializing model"),
                "initialization is classified as loading");
        assertEquals(NativeStatus.Phase.PROCESSING, NativeStatus.classify("Transcribing..."),
                "transcription is classified as processing");
        assertEquals(NativeStatus.Phase.WAITING, NativeStatus.classify("Waiting for audio"),
                "waiting remains the diagnostic idle phase");
        assertEquals("ready", NativeStatus.diagnosticLabel("Ready"),
                "ready status has a content-free diagnostic label");
        assertEquals("recording", NativeStatus.diagnosticLabel("Listening..."),
                "normal microphone capture is distinguishable from an unknown status");
        assertEquals("cancelled", NativeStatus.diagnosticLabel("Canceled"),
                "a requested cancellation is distinguishable from an error");
        assertEquals(NativeStatus.Phase.UNKNOWN, NativeStatus.classify("Listening..."),
                "diagnostic capture labeling does not alter existing UI phase rules");
        assertEquals("unknown", NativeStatus.diagnosticLabel("Listening... private text"),
                "only the exact fixed capture status receives the recording label");
        assertEquals("error", NativeStatus.diagnosticLabel("Error: Listening..."),
                "an error remains an error rather than a capture status");
        assertEquals("unknown", NativeStatus.diagnosticLabel(null),
                "missing status fails closed without throwing");
    }

    private static void recommendationFiltersBeforeRanking() {
        ModelRecommendation.DeviceProfile capable = new ModelRecommendation.DeviceProfile(
                true, true, true, 4L << 30, 2L << 30, null);
        ModelRecommendation.Result accepted = ModelRecommendation.recommend(
                capable, new ModelRecommendation.Request("fr-FR", false));
        assertEquals(ModelRecommendation.Status.RECOMMENDED, accepted.status,
                "a compatible device and supported language get the verified model");

        ModelRecommendation.Result german = ModelRecommendation.recommend(
                capable, new ModelRecommendation.Request("de-DE", false));
        assertEquals("parakeet-primeline-Q4_K_M.gguf", german.model.fileName,
                "balanced German guidance chooses the tuned primeLine model");

        ModelRecommendation.Result language = ModelRecommendation.recommend(
                capable, new ModelRecommendation.Request("zh-CN", false));
        assertEquals(ModelRecommendation.Status.RECOMMENDED, language.status,
                "balanced guidance chooses a multilingual fallback when Parakeet lacks the language");
        assertEquals("whisper-small-Q4_K_M.gguf", language.model.fileName,
                "the multilingual fallback remains practical on a capable phone");

        ModelRecommendation.Result translation = ModelRecommendation.recommend(
                capable, new ModelRecommendation.Request("fr-FR", true));
        assertEquals(ModelRecommendation.Status.RECOMMENDED, translation.status,
                "translation selects a model family that supports translation");
        assertEquals("whisper-small-Q4_K_M.gguf", translation.model.fileName,
                "translation does not suggest the non-translating default");
        ModelRecommendation.Result automaticTranslation = ModelRecommendation.recommend(
                capable, new ModelRecommendation.Request(
                        "", true, ModelRecommendation.Goal.BALANCED));
        assertEquals("whisper-small-Q4_K_M.gguf", automaticTranslation.model.fileName,
                "balanced translation with automatic language still chooses Whisper");

        ModelRecommendation.Result storage = ModelRecommendation.recommend(
                new ModelRecommendation.DeviceProfile(true, true, true, 4L << 30,
                        DefaultModelContract.BYTE_SIZE, null),
                new ModelRecommendation.Request("en-US", false));
        assertEquals(ModelRecommendation.Status.RECOMMENDED, storage.status,
                "balanced guidance falls back when the default lacks storage headroom");
        assertEquals("whisper-small-Q4_K_M.gguf", storage.model.fileName,
                "a smaller verified model is recommended instead of a dead end");

        ModelRecommendation.Result compact = ModelRecommendation.recommend(
                capable, new ModelRecommendation.Request(
                        "en-US", false, ModelRecommendation.Goal.COMPACT));
        assertEquals("whisper-tiny.en-Q4_K_M.gguf", compact.model.fileName,
                "compact English chooses the smallest English Whisper variant");
        ModelRecommendation.Result multilingual = ModelRecommendation.recommend(
                capable, new ModelRecommendation.Request(
                        "zh-CN", false, ModelRecommendation.Goal.MULTILINGUAL));
        assertEquals("whisper-small-Q4_K_M.gguf", multilingual.model.fileName,
                "broad-language choice falls back to a phone-sized multilingual model");
        ModelRecommendation.Result cantonese = ModelRecommendation.recommend(
                new ModelRecommendation.DeviceProfile(
                        true, true, true, 8L << 30, 4L << 30, null),
                new ModelRecommendation.Request(
                        "yue-HK", false, ModelRecommendation.Goal.MULTILINGUAL));
        assertEquals("whisper-large-v3-turbo-Q4_K_M.gguf", cantonese.model.fileName,
                "Cantonese routes to the smallest compatible Whisper v3 model");
        ModelRecommendation.Result translating = ModelRecommendation.recommend(
                capable, new ModelRecommendation.Request(
                        "de-DE", true, ModelRecommendation.Goal.TRANSLATE));
        assertEquals("whisper-small-Q4_K_M.gguf", translating.model.fileName,
                "translation chooses a proven non-Turbo multilingual Whisper model");
    }

    private static void downloadPolicyIsStrictAboutResumeAndIntegrity() {
        assertEquals(ModelDownloadPolicy.ResumeAction.APPEND,
                ModelDownloadPolicy.resumeAction(100, 206, "bytes 100-999/1000", "v1", "v1"),
                "a matching ranged response resumes");
        assertEquals(ModelDownloadPolicy.ResumeAction.RESTART,
                ModelDownloadPolicy.resumeAction(100, 200, null, "v1", "v1"),
                "a server ignoring Range restarts safely");
        assertEquals(ModelDownloadPolicy.ResumeAction.RESTART,
                ModelDownloadPolicy.resumeAction(100, 206, "bytes 100-999/1000", "v1", "v2"),
                "a changed validator restarts safely");
        assertEquals(false, ModelDownloadPolicy.acceptArtifact(
                        DefaultModelContract.BYTE_SIZE - 1, DefaultModelContract.SHA256,
                        ModelCatalog.verifiedDefault()),
                "a truncated artifact never activates");
        assertEquals(true, ModelDownloadPolicy.acceptArtifact(
                        DefaultModelContract.BYTE_SIZE, DefaultModelContract.SHA256,
                        ModelCatalog.verifiedDefault()),
                "only exact bytes and digest activate");
    }

    private static void downloadTrustBoundaryRejectsUntrustedHostsAndDiscardsCancelledStaging()
            throws Exception {
        ModelDownloadTask.requireAllowedHttps(new URL(
                "https://cdn-lfs.huggingface.co/model.gguf"));
        assertThrows(java.io.IOException.class,
                () -> ModelDownloadTask.requireAllowedHttps(new URL(
                        "http://huggingface.co/model.gguf")),
                "cleartext model downloads are rejected");
        assertThrows(java.io.IOException.class,
                () -> ModelDownloadTask.requireAllowedHttps(new URL(
                        "https://huggingface.co.attacker.example/model.gguf")),
                "lookalike model hosts are rejected");

        byte[] expected = "complete model bytes".getBytes(StandardCharsets.UTF_8);
        Path filesDir = Files.createTempDirectory("offline-stt-download-cancel-test");
        ModelDescriptor descriptor = testDescriptor("cancel-model", "cancel.gguf", expected,
                "https://example.invalid/model.gguf");
        Path downloads = filesDir.resolve("models").resolve(".downloads");
        Files.createDirectories(downloads);
        Path part = downloads.resolve(descriptor.id + ".part");
        Path validator = downloads.resolve(descriptor.id + ".validator");
        Files.writeString(part, "partial model bytes", StandardCharsets.UTF_8);
        Files.writeString(validator, "etag", StandardCharsets.UTF_8);

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>();
        ModelDownloadTask.start(filesDir.toFile(), descriptor,
                new ModelDownloadTask.Listener() {
                    @Override public void onStarted(ModelDownloadTask.Handle handle) {
                        handle.cancel();
                    }
                    @Override public void onProgress(long downloaded, long total) {}
                    @Override public void onComplete(String installedName) {
                        outcome.set("completed");
                        finished.countDown();
                    }
                    @Override public void onFailure(String reason) {
                        outcome.set(reason);
                        finished.countDown();
                    }
                });

        assertEquals(true, finished.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "cancelled download reaches a bounded terminal callback");
        assertEquals("Download cancelled.", outcome.get(),
                "cancelled download reports the safe cancellation reason");
        assertEquals(false, Files.exists(part),
                "cancel removes the hidden staged model bytes");
        assertEquals(false, Files.exists(validator),
                "cancel removes the stale resume validator");
    }

    private static void configurationChangesKeepDownloadOwnershipAlive() {
        ModelDownloadLifecycle lifecycle = new ModelDownloadLifecycle();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<ModelDownloadTask.Listener> taskListener = new AtomicReference<>();
        List<ModelDownloadLifecycle.Snapshot> firstUi = new ArrayList<>();
        List<ModelDownloadLifecycle.Snapshot> recreatedUi = new ArrayList<>();
        ModelDownloadLifecycle.Observer firstObserver = firstUi::add;
        ModelDownloadLifecycle.Observer recreatedObserver = recreatedUi::add;

        lifecycle.attach(firstObserver);
        assertEquals(true, lifecycle.start("model-a", "catalog", listener -> {
            taskListener.set(listener);
            ModelDownloadTask.Handle handle = new ModelDownloadTask.Handle(cancelled);
            listener.onStarted(handle);
            return handle;
        }), "the lifecycle owner starts one explicit download");
        taskListener.get().onProgress(5, 10);
        lifecycle.detach(firstObserver);
        lifecycle.attach(recreatedObserver);

        ModelDownloadLifecycle.Snapshot restored =
                recreatedUi.get(recreatedUi.size() - 1);
        assertEquals(ModelDownloadLifecycle.State.RUNNING, restored.state,
                "recreated UI observes the existing download owner");
        assertEquals(5L, restored.downloaded,
                "recreated UI receives retained progress");
        assertEquals("model-a", restored.modelId,
                "recreated UI keeps the exact card that owns progress");
        assertEquals("catalog", restored.origin,
                "recreated UI keeps the surface that started the download");
        lifecycle.cancel();
        assertEquals(true, cancelled.get(),
                "recreated UI can cancel the retained task handle");

        ModelDownloadLifecycle completed = new ModelDownloadLifecycle();
        AtomicReference<ModelDownloadTask.Listener> completingTask = new AtomicReference<>();
        ModelDownloadLifecycle.Observer detached = snapshot -> {};
        completed.attach(detached);
        completed.start("model-b", "recommended", listener -> {
            completingTask.set(listener);
            ModelDownloadTask.Handle handle =
                    new ModelDownloadTask.Handle(new AtomicBoolean());
            listener.onStarted(handle);
            return handle;
        });
        completed.detach(detached);
        completingTask.get().onComplete("verified.gguf");
        List<ModelDownloadLifecycle.Snapshot> restoredCompletion = new ArrayList<>();
        completed.attach(restoredCompletion::add);
        assertEquals(ModelDownloadLifecycle.State.COMPLETE,
                restoredCompletion.get(restoredCompletion.size() - 1).state,
                "terminal completion is delivered after recreation");

        ModelDownloadLifecycle failed = new ModelDownloadLifecycle();
        AtomicReference<ModelDownloadTask.Listener> failingTask = new AtomicReference<>();
        failed.start("model-c", "catalog", listener -> {
            failingTask.set(listener);
            return new ModelDownloadTask.Handle(new AtomicBoolean());
        });
        failingTask.get().onFailure("Downloaded file failed integrity verification.");
        List<ModelDownloadLifecycle.Snapshot> restoredFailure = new ArrayList<>();
        AtomicBoolean firstFailureUiAnnounced = new AtomicBoolean();
        ModelDownloadLifecycle.Observer firstFailureObserver = snapshot -> {
            restoredFailure.add(snapshot);
            if (snapshot.state == ModelDownloadLifecycle.State.FAILED) {
                firstFailureUiAnnounced.set(failed.consumeFailureAnnouncement());
            }
        };
        failed.attach(firstFailureObserver);
        assertEquals(ModelDownloadLifecycle.State.FAILED,
                restoredFailure.get(restoredFailure.size() - 1).state,
                "failed model id remains available for the card-local Retry label");
        assertEquals(true, firstFailureUiAnnounced.get(),
                "the first visible UI consumes the failure announcement");
        failed.detach(firstFailureObserver);
        List<ModelDownloadLifecycle.Snapshot> secondFailureUi = new ArrayList<>();
        failed.attach(snapshot -> {
            secondFailureUi.add(snapshot);
            assertEquals(false, failed.consumeFailureAnnouncement(),
                    "recreated UI does not replay the failure snackbar or modal dialog");
        });
    }

    private static void modelGuidanceUsesConservativeSizeAndDeviceTiers() {
        ModelDescriptor compact = ModelCatalog.findByFileName("whisper-tiny-Q4_K_M.gguf");
        ModelDescriptor phone = ModelCatalog.verifiedDefault();
        ModelDescriptor huge = ModelCatalog.findByFileName(
                "Voxtral-Small-24B-2507-Q4_K_M.gguf");
        assertEquals(ModelDescriptor.SizeTier.COMPACT, compact.sizeTier(),
                "sub-128 MiB models are called compact");
        assertEquals(ModelDescriptor.SizeTier.PHONE_FRIENDLY, phone.sizeTier(),
                "the bundled default stays in the phone-friendly tier");
        assertEquals(ModelDescriptor.SizeTier.VERY_LARGE, huge.sizeTier(),
                "multi-gigabyte models are not presented as ordinary phone downloads");
        ModelRecommendation.DeviceProfile ordinaryPhone = new ModelRecommendation.DeviceProfile(
                true, true, true, 4L << 30, 4L << 30, null);
        assertEquals(ModelGuidance.DeviceFit.GOOD,
                ModelGuidance.fit(phone, ordinaryPhone),
                "a compatible phone gets a non-promissory good-fit label");
        assertEquals(ModelGuidance.DeviceFit.NOT_ENOUGH_RAM,
                ModelGuidance.fit(huge, ordinaryPhone),
                "large models are rejected before download when estimated RAM is insufficient");
        ModelDescriptor ordinaryParakeet = ModelGuidance.largestComfortableInFamily(
                ModelCatalog.verifiedDownloads(), "Parakeet", ordinaryPhone);
        assertEquals(DefaultModelContract.FILE_NAME, ordinaryParakeet.fileName,
                "a 4 GiB phone highlights the largest conservative Parakeet fit");
        ModelRecommendation.DeviceProfile highMemoryPhone =
                new ModelRecommendation.DeviceProfile(
                        true, true, true, 12L << 30, 16L << 30, null);
        ModelDescriptor largeCanary = ModelGuidance.largestComfortableInFamily(
                ModelCatalog.verifiedDownloads(), "Canary", highMemoryPhone);
        assertEquals("canary-qwen-2.5b-Q4_K_M.gguf", largeCanary.fileName,
                "a high-memory phone can highlight a larger comfortable family option");
    }

    private static void importedModelNamesStayInsidePrivateModelStorage() {
        assertEquals(true, ModelInstaller.isSafeModelName("local-model.gguf"),
                "ordinary GGUF names are accepted");
        assertEquals(false, ModelInstaller.isSafeModelName("../outside.gguf"),
                "parent traversal is rejected before creating a part file");
        assertEquals(false, ModelInstaller.isSafeModelName("folder/model.gguf"),
                "nested paths are rejected before creating a part file");
        assertEquals(false, ModelInstaller.isSafeModelName(" model.gguf"),
                "ambiguous padded names are rejected");
    }

    private static void verifiedInstallerRechecksStagedBytesAndDestinationPath()
            throws Exception {
        byte[] approved = "approved model bytes".getBytes(StandardCharsets.UTF_8);
        ModelDescriptor descriptor = testDescriptor("verified.gguf", approved);
        Path tamperedFiles = Files.createTempDirectory("offline-stt-installer-tampered-test");
        Path tamperedStage = tamperedFiles.resolve("staged.part");
        Files.writeString(tamperedStage, "tampered model bytes", StandardCharsets.UTF_8);

        assertThrows(java.io.IOException.class,
                () -> ModelInstaller.installVerified(
                        tamperedFiles.toFile(), tamperedStage.toFile(), descriptor),
                "installer rechecks staged bytes at the final trust boundary");
        assertEquals(false, Files.exists(tamperedFiles.resolve("models").resolve("verified.gguf")),
                "tampered staged bytes never reach model storage");
        assertEquals(true, Files.exists(tamperedStage),
                "rejected staged bytes remain available to the caller for cleanup");

        Path unsafeFiles = Files.createTempDirectory("offline-stt-installer-path-test");
        Path unsafeStage = unsafeFiles.resolve("staged.part");
        Files.write(unsafeStage, approved);
        ModelDescriptor unsafe = testDescriptor("../outside.gguf", approved);
        assertThrows(java.io.IOException.class,
                () -> ModelInstaller.installVerified(
                        unsafeFiles.toFile(), unsafeStage.toFile(), unsafe),
                "verified installation rejects a destination outside model storage");
        assertEquals(false, Files.exists(unsafeFiles.resolve("outside.gguf")),
                "unsafe descriptor paths never create files outside model storage");

        Path cancelledFiles = Files.createTempDirectory("offline-stt-installer-cancel-test");
        Path cancelledStage = cancelledFiles.resolve("staged.part");
        Files.write(cancelledStage, approved);
        assertThrows(java.io.IOException.class,
                () -> ModelInstaller.installVerified(cancelledFiles.toFile(),
                        cancelledStage.toFile(), descriptor, () -> true),
                "cancellation is rechecked at the final install boundary");
        assertEquals(false,
                Files.exists(cancelledFiles.resolve("models").resolve("verified.gguf")),
                "a cancelled download never activates after hashing");
        assertEquals(true, Files.exists(cancelledStage),
                "cancelled staged bytes remain available for bounded cleanup");

        Path validFiles = Files.createTempDirectory("offline-stt-installer-valid-test");
        Path validStage = validFiles.resolve("staged.part");
        Files.write(validStage, approved);
        assertEquals("verified.gguf", ModelInstaller.installVerified(
                        validFiles.toFile(), validStage.toFile(), descriptor),
                "exact staged bytes install successfully");
        assertEquals("verified.gguf", Files.readString(validFiles.resolve("active_model")),
                "verified installation atomically activates the accepted model");
        assertEquals(true, VerifiedModelProvenance.isVerified(
                        validFiles.toFile(),
                        validFiles.resolve("models/verified.gguf").toFile(), descriptor),
                "verified installation persists provenance for capability labels");
    }

    private static void verifiedModelLabelsRequirePersistedProvenance() throws Exception {
        byte[] bytes = "verified provenance".getBytes(StandardCharsets.UTF_8);
        ModelDescriptor descriptor = testDescriptor(
                "verified-provenance", "recommended.gguf", bytes,
                "https://huggingface.co/example/repo/resolve/commit/recommended.gguf");
        Path filesDir = Files.createTempDirectory("offline-stt-provenance-test");
        Path modelsDir = Files.createDirectories(filesDir.resolve("models"));
        Path model = modelsDir.resolve(descriptor.fileName);
        Files.write(model, bytes);

        assertEquals(false, VerifiedModelProvenance.isVerified(
                        filesDir.toFile(), model.toFile(), descriptor),
                "filename and size alone do not claim verified model provenance");
        VerifiedModelProvenance.markVerified(
                filesDir.toFile(), model.toFile(), descriptor);
        assertEquals(true, VerifiedModelProvenance.isVerified(
                        filesDir.toFile(), model.toFile(), descriptor),
                "installer-owned provenance enables verified model metadata");
        VerifiedModelProvenance.forget(filesDir.toFile(), descriptor.fileName);
        assertEquals(false, VerifiedModelProvenance.isVerified(
                        filesDir.toFile(), model.toFile(), descriptor),
                "deletion clears verified provenance before a same-name import");
    }

    private static ModelDescriptor testDescriptor(String fileName, byte[] bytes) throws Exception {
        return testDescriptor("test-model", fileName, bytes,
                "https://huggingface.co/example/model.gguf");
    }

    private static ModelDescriptor testDescriptor(String id, String fileName, byte[] bytes,
                                                  String downloadUrl) throws Exception {
        String digest = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new ModelDescriptor(id, "Test model", "test", "test", fileName,
                bytes.length, digest, downloadUrl,
                "https://huggingface.co/example", "test-license",
                "https://example.invalid/license", Collections.singleton("arm64-v8a"),
                false, false, Collections.singleton("en"),
                ModelDescriptor.Capability.SUPPORTED, ModelDescriptor.Capability.UNSUPPORTED,
                ModelDescriptor.Capability.UNKNOWN, ModelDescriptor.Capability.UNKNOWN,
                1, 1, ModelDescriptor.Provenance.EXACT_PINNED);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static void setupStatesAreExplicit() {
        assertEquals(SetupState.Microphone.NOT_REQUESTED,
                SetupState.microphone(false, false, false), "mic not requested");
        assertEquals(SetupState.Microphone.DENIED,
                SetupState.microphone(false, true, true), "mic denied with retry");
        assertEquals(SetupState.Microphone.DENIED_PERMANENTLY,
                SetupState.microphone(false, true, false), "mic denied permanently");
        assertEquals(SetupState.Microphone.GRANTED,
                SetupState.microphone(true, true, false), "mic granted");
        assertEquals(SetupState.Ime.DISABLED,
                SetupState.ime(false, false), "IME disabled");
        assertEquals(SetupState.Ime.ENABLED,
                SetupState.ime(true, false), "IME enabled");
        assertEquals(SetupState.Ime.SELECTED,
                SetupState.ime(true, true), "IME selected");
    }

    private static void imeEditorSafetyIsFailClosed() {
        assertEquals(false, ImeTextEdit.isSensitiveEditor(0x00000001, 0),
                "ordinary text fields allow local dictation");
        assertEquals(true, ImeTextEdit.isSensitiveEditor(0x00000081, 0),
                "text passwords reject dictation");
        assertEquals(true, ImeTextEdit.isSensitiveEditor(0x00000091, 0),
                "visible text passwords reject dictation");
        assertEquals(true, ImeTextEdit.isSensitiveEditor(0x000000e1, 0),
                "web passwords reject dictation");
        assertEquals(true, ImeTextEdit.isSensitiveEditor(0x00000012, 0),
                "numeric passwords reject dictation");
        assertEquals(false, ImeTextEdit.isSensitiveEditor(0x00000001, 0x01000000),
                "private no-learning fields allow content-free local dictation");
    }

    private static void recordingSessionTracksTheSafeEditorLifecycleNotWrapperIdentity() {
        Object originalEditor = new Object();
        Object replacementEditor = new Object();
        ImeRecordingSession session = new ImeRecordingSession();

        session.begin(originalEditor, 7L);
        assertEquals(true, session.canCommitTo(originalEditor, 7L, true, true),
                "a transcription may commit to its original active safe editor");
        assertEquals(true, session.canCommitTo(replacementEditor, 7L, true, true),
                "a replacement IPC wrapper remains valid in the same editor lifecycle");
        assertEquals(false, session.canCommitTo(originalEditor, 8L, true, true),
                "a transcription never commits after Android starts another editor");
        assertEquals(true, session.canDeliverToCurrent(replacementEditor, true, true),
                "a completed result follows Android's current safe composer");
        assertEquals(false, session.canDeliverToCurrent(null, true, true),
                "delivery waits while a recreated composer has no connection");
        assertEquals(false, session.canDeliverToCurrent(replacementEditor, false, true),
                "delivery never targets a sensitive replacement composer");
        assertEquals(false, session.canCommitTo(originalEditor, 7L, false, true),
                "a transcription never commits to a sensitive editor");
        assertEquals(false, session.canCommitTo(originalEditor, 7L, true, false),
                "a transcription never commits without active input");

        ImeRecordingSession failedSession = new ImeRecordingSession();
        failedSession.begin(originalEditor, 7L);
        failedSession.onError();
        assertEquals(false, failedSession.canCommitTo(originalEditor, 7L, true, true),
                "an error fail-closes an otherwise active recording");
        assertEquals(false, failedSession.canDeliverToCurrent(replacementEditor, true, true),
                "an error also blocks delivery to a replacement composer");

        session.discard();
        session.onError();
        assertEquals(false, session.canCommitTo(originalEditor, 7L, true, true),
                "an error cannot clear the discard latch for a late transcription");

        session.begin(originalEditor, 9L);
        assertEquals(true, session.canCommitTo(originalEditor, 9L, true, true),
                "only a new recording clears the discard latch");
        session.finish();
        assertEquals(false, session.canCommitTo(originalEditor, 9L, true, true),
                "a completed session cannot replay into an editor");
    }

    private static void deletePreviewMovesByWholeGraphemeClusters() {
        String text = "A👩‍💻e\u0301אב";
        int cursor = "A👩‍💻e\u0301".length();

        ImeTextEdit.Range previous = ImeTextEdit.selectionForClusterDelta(text, cursor, -1);
        assertEquals("e\u0301", text.substring(previous.start, previous.end),
                "combining sequence remains one deletion unit");

        ImeTextEdit.Range emoji = ImeTextEdit.selectionForClusterDelta(text, cursor, -2);
        assertEquals("👩‍💻e\u0301", text.substring(emoji.start, emoji.end),
                "ZWJ emoji remains one deletion unit");

        ImeTextEdit.Range rtl = ImeTextEdit.selectionForClusterDelta(text, cursor, 2);
        assertEquals("אב", text.substring(rtl.start, rtl.end),
                "forward preview supports RTL text without reordering it");

        ImeTextEdit.Range oddRegionalRun = ImeTextEdit.selectionForClusterDelta("🇦🇧🇨", "🇦🇧🇨".length(), -1);
        assertEquals("🇨", "🇦🇧🇨".substring(oddRegionalRun.start, oddRegionalRun.end),
                "an odd regional-indicator run leaves its final indicator unpaired");
        ImeTextEdit.Range crlf = ImeTextEdit.selectionForClusterDelta("\r\n", 2, -1);
        assertEquals("\r\n", "\r\n".substring(crlf.start, crlf.end),
                "CRLF remains one grapheme cluster");
        ImeTextEdit.Range internalCombining = ImeTextEdit.selectionForClusterDelta("e\u0301", 1, 1);
        assertEquals("e\u0301", "e\u0301".substring(internalCombining.start, internalCombining.end),
                "a cursor inside a grapheme snaps to a safe cluster boundary");

        for (String cluster : new String[]{"각", "क्‍ष", "🏴󠁧󠁢󠁥󠁮󠁧󠁿"}) {
            ImeTextEdit.Range complex = ImeTextEdit.selectionForClusterDelta(cluster, cluster.length(), -1);
            assertEquals(cluster, cluster.substring(complex.start, complex.end),
                    "complex Unicode sequence remains one deletion unit");
        }

        ImeTextEdit.Range leftBoundary = ImeTextEdit.selectionForClusterDelta(text, 0, -20);
        assertEquals(new ImeTextEdit.Range(0, 0), leftBoundary,
                "preview clamps at the start of the editor");
        ImeTextEdit.Range rightBoundary = ImeTextEdit.selectionForClusterDelta(text, text.length(), 20);
        assertEquals(new ImeTextEdit.Range(text.length(), text.length()), rightBoundary,
                "preview clamps at the end of the editor");

        String conjunctAndPunctuation = "क्ष,";
        assertEquals(new ImeTextEdit.Range(0, 3),
                ImeTextEdit.selectionForClusterDelta(conjunctAndPunctuation, 0, 1),
                "virama conjuncts stay whole before punctuation");
        assertEquals(new ImeTextEdit.Range(3, 4),
                ImeTextEdit.selectionForClusterDelta(conjunctAndPunctuation, 3, 1),
                "punctuation after a virama conjunct remains its own cluster");
        assertEquals(new ImeTextEdit.Range(2, 3),
                ImeTextEdit.selectionForClusterDelta("क्,", 2, 1),
                "punctuation after a terminal virama is not absorbed");
        assertEquals(new ImeTextEdit.Range(2, 3),
                ImeTextEdit.selectionForClusterDelta("a‍ ", 2, 1),
                "whitespace after a dangling ZWJ is not absorbed");
    }

    private static void deletePreviewExtendsAnExistingSelectionByCluster() {
        String family = "ab👨‍👩‍👧‍👦cd";
        int familyEnd = family.offsetByCodePoints(2, 7);
        assertEquals(new ImeTextEdit.Range(1, familyEnd),
                ImeTextEdit.selectionForClusterDelta(family, 2, familyEnd, -1),
                "dragging left extends the existing selection without dropping it");
        assertEquals(new ImeTextEdit.Range(2, familyEnd + 1),
                ImeTextEdit.selectionForClusterDelta(family, 2, familyEnd, 1),
                "dragging right extends the existing selection without dropping it");
    }

    private static void settingsMigrationPreservesUnknownAndFutureState() throws Exception {
        Path filesDir = Files.createTempDirectory("offline-stt-settings-test");
        Path model = filesDir.resolve("models").resolve("custom.gguf");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "model-bytes", StandardCharsets.UTF_8);
        Path unknown = filesDir.resolve("future_setting");
        Files.writeString(unknown, "keep", StandardCharsets.UTF_8);
        Files.writeString(filesDir.resolve(".settings_schema.tmp"), "interrupted",
                StandardCharsets.UTF_8);

        assertEquals(SettingsSchema.CURRENT, SettingsSchema.migrate(filesDir.toFile()),
                "legacy settings migrate to current schema");
        assertEquals("model-bytes", Files.readString(model, StandardCharsets.UTF_8),
                "migration preserves imported models");
        assertEquals("keep", Files.readString(unknown, StandardCharsets.UTF_8),
                "migration preserves unknown settings");

        Files.writeString(filesDir.resolve(".settings_schema"), "99", StandardCharsets.UTF_8);
        assertEquals(99, SettingsSchema.migrate(filesDir.toFile()),
                "future schema is not downgraded");
        assertEquals("99", Files.readString(filesDir.resolve(".settings_schema"),
                StandardCharsets.UTF_8), "future schema marker is unchanged");
    }

    private static void diagnosticLoggingIsFailClosedAndBounded() throws Exception {
        Path filesDir = Files.createTempDirectory("offline-stt-diagnostic-log-test");
        Path disabledDir = filesDir.resolve("disabled");
        DiagnosticLog.event(disabledDir.toFile(), "app_start", "process=app");
        DiagnosticLog.recognizerError(disabledDir.toFile(), 9);
        assertEquals(false, Files.exists(disabledDir),
                "disabled logging returns before touching the file system");
        DiagnosticLog.event(filesDir.toFile(), "app_start", "process=app");
        assertEquals(false, Files.exists(filesDir.resolve("diagnostic.log")),
                "diagnostic logging defaults off");

        DiagnosticLog.setEnabled(filesDir.toFile(), true);
        DiagnosticLog.event(filesDir.toFile(), "app_start", "process=app");
        assertEquals(true, DiagnosticLog.awaitIdleForTest(5_000), "diagnostic writer drains");
        String first = Files.readString(filesDir.resolve("diagnostic.log"), StandardCharsets.UTF_8);
        assertEquals(true, first.contains("app_start process=app"),
                "allowlisted metadata is recorded while enabled");
        byte[] exported = DiagnosticLog.snapshot(filesDir.toFile());
        assertEquals(first, new String(exported, StandardCharsets.UTF_8),
                "export snapshot contains the complete bounded private log");

        DiagnosticLog.event(filesDir.toFile(), "timing",
                "process=ime,audio_ms=11000,duration_ms=900,cpu_threads=2");
        DiagnosticLog.event(filesDir.toFile(), "timing",
                "process=ime,duration_ms=12000,paused_ms=1500");
        assertEquals(true, DiagnosticLog.awaitIdleForTest(5_000),
                "content-free power diagnostics drain");
        String metrics = Files.readString(filesDir.resolve("diagnostic.log"),
                StandardCharsets.UTF_8);
        assertEquals(true, metrics.contains("audio_ms=11000,duration_ms=900,cpu_threads=2"),
                "inference power metadata is allowlisted");
        assertEquals(true, metrics.contains("duration_ms=12000,paused_ms=1500"),
                "capture power metadata is allowlisted");

        DiagnosticLog.event(filesDir.toFile(), "ime_start", "process=ime,version_code=46");
        DiagnosticLog.event(filesDir.toFile(), "ime_state",
                "state=" + NativeStatus.diagnosticLabel("Listening..."));
        DiagnosticLog.event(filesDir.toFile(), "ime_state",
                "state=" + NativeStatus.diagnosticLabel("Canceled"));
        for (int errorCode : new int[] {3, 4, 5, 7, 9, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            DiagnosticLog.recognizerError(filesDir.toFile(), errorCode);
        }
        assertEquals(true, DiagnosticLog.awaitIdleForTest(5_000),
                "fixed status, version and numeric error evidence drains");
        String lifecycle = Files.readString(filesDir.resolve("diagnostic.log"),
                StandardCharsets.UTF_8);
        for (String expected : new String[] {
                "ime_start process=ime,version_code=46", "ime_state state=recording",
                "ime_state state=cancelled", "error_code process=service,code=E_RECOGNIZER_3",
                "code=E_RECOGNIZER_4", "code=E_RECOGNIZER_5", "code=E_RECOGNIZER_7",
                "code=E_RECOGNIZER_9", "code=E_RECOGNIZER_2147483647",
                "code=E_RECOGNIZER_UNKNOWN"}) {
            assertEquals(true, lifecycle.contains(expected),
                    "diagnostic snapshot preserves content-free evidence: " + expected);
        }

        for (int reason = 0; reason <= 3; reason++) {
            DiagnosticLog.recognitionOutcome(filesDir.toFile(), reason, 1600);
        }
        DiagnosticLog.recognitionOutcome(filesDir.toFile(), 1, Long.MAX_VALUE);
        DiagnosticLog.recognitionOutcome(filesDir.toFile(), 1, -1);
        DiagnosticLog.recognitionOutcome(filesDir.toFile(), 99, 777);
        DiagnosticLog.event(filesDir.toFile(), "recognition_outcome", "outcome=secret");
        assertEquals(true, DiagnosticLog.awaitIdleForTest(5_000), "outcome writer drains");
        String outcomes = Files.readString(filesDir.resolve("diagnostic.log"));
        for (String label : new String[] {"text", "insufficient_audio", "empty_decode", "input_error"}) {
            assertEquals(true, outcomes.contains("outcome=" + label + ",audio_ms=1600"),
                    "fixed outcome metadata recorded: " + label);
        }
        assertEquals(true, outcomes.contains("audio_ms=999999999999"), "duration is bounded");
        assertEquals(true, outcomes.contains("outcome=insufficient_audio,audio_ms=0"), "negative duration clamped");
        assertEquals(false, outcomes.contains("audio_ms=777"), "unknown reason is discarded");

        DiagnosticLog.event(filesDir.toFile(), "transcript", "secret words");
        DiagnosticLog.event(filesDir.toFile(), "ime_state", "editor=secret words");
        DiagnosticLog.event(filesDir.toFile(), "ime_state", "state=secret");
        DiagnosticLog.event(filesDir.toFile(), "ime_start", "process=ime,version_code=secret");
        DiagnosticLog.event(filesDir.toFile(), "error_code", "process=service,message=secret");
        assertEquals(true, DiagnosticLog.awaitIdleForTest(5_000), "rejected events do not stall writer");
        String safe = Files.readString(filesDir.resolve("diagnostic.log"), StandardCharsets.UTF_8);
        assertEquals(false, safe.contains("secret"),
                "speech and editor content are structurally rejected");

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> concurrentFailure = new AtomicReference<>();
        Thread[] writers = new Thread[8];
        for (int thread = 0; thread < writers.length; thread++) {
            final int writer = thread;
            writers[thread] = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        DiagnosticLog.event(filesDir.toFile(), "timing",
                                "elapsed_ms=" + (writer * 200 + i));
                    }
                } catch (Throwable error) {
                    concurrentFailure.compareAndSet(null, error);
                }
            });
            writers[thread].start();
        }
        start.countDown();
        for (Thread writer : writers) writer.join();
        assertEquals(true, DiagnosticLog.awaitIdleForTest(5_000), "concurrent events drain");
        assertEquals(false, concurrentFailure.get() != null,
                "same-process concurrent diagnostics never throw");

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = System.getProperty("java.class.path");
        Process firstWriter = new ProcessBuilder(java, "-cp", classPath,
                BehaviorTest.class.getName(), "diagnostic-writer", filesDir.toString(), "400").start();
        Process secondWriter = new ProcessBuilder(java, "-cp", classPath,
                BehaviorTest.class.getName(), "diagnostic-writer", filesDir.toString(), "400").start();
        assertEquals(0, firstWriter.waitFor(), "first diagnostic process exits cleanly");
        assertEquals(0, secondWriter.waitFor(), "second diagnostic process exits cleanly");
        String crossProcessLog = Files.readString(filesDir.resolve("diagnostic.log"));
        assertEquals(true, crossProcessLog.endsWith("\n"),
                "cross-process log ends at a record boundary");
        Pattern record = Pattern.compile(
                "^\\d+ (?:app_start|ime_start|ime_state|error_code|recognition_outcome|timing|resource)(?: [a-z_]+=[a-zA-Z0-9_.:/-]+(?:,[a-z_]+=[a-zA-Z0-9_.:/-]+)*)?$");
        for (String line : crossProcessLog.split("\\R")) {
            assertEquals(true, record.matcher(line).matches(),
                    "cross-process log record is intact");
        }

        for (int i = 0; i < 5000; i++) {
            DiagnosticLog.event(filesDir.toFile(), "timing", "elapsed_ms=" + i);
            if (i % 200 == 199) {
                assertEquals(true, DiagnosticLog.awaitIdleForTest(5_000),
                        "retention batch drains");
            }
        }
        assertEquals(true, DiagnosticLog.awaitIdleForTest(5_000), "retention events drain");
        long retainedSize = Files.size(filesDir.resolve("diagnostic.log"));
        assertEquals(true, retainedSize > 0 && retainedSize <= DiagnosticLog.MAX_BYTES,
                "diagnostic log retention is bounded");

        DiagnosticLog.setEnabled(filesDir.toFile(), false);
        long disabledSize = Files.size(filesDir.resolve("diagnostic.log"));
        DiagnosticLog.event(filesDir.toFile(), "app_start", "process=ime");
        assertEquals(disabledSize, Files.size(filesDir.resolve("diagnostic.log")),
                "disabling is immediate for every process");
        DiagnosticLog.clear(filesDir.toFile());
        assertEquals(false, Files.exists(filesDir.resolve("diagnostic.log")),
                "clear removes retained diagnostics");
        assertEquals(0, DiagnosticLog.snapshot(filesDir.toFile()).length,
                "cleared diagnostics export as an empty snapshot");
        Files.write(filesDir.resolve("diagnostic.log"),
                new byte[(int) DiagnosticLog.MAX_BYTES + 1]);
        assertThrows(IOException.class, () -> DiagnosticLog.snapshot(filesDir.toFile()),
                "an unexpectedly oversized private log fails closed instead of being shared");
        DiagnosticLog.clear(filesDir.toFile());
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertThrows(Class<? extends Throwable> expected,
                                     ThrowingRunnable action, String message) {
        try {
            action.run();
        } catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError(message + ": expected " + expected.getName()
                    + ", got " + error.getClass().getName(), error);
        }
        throw new AssertionError(message + ": expected " + expected.getName());
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
