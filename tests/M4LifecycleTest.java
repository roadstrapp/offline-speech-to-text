package dev.notune.transcribe;

import java.util.concurrent.atomic.AtomicLong;

public final class M4LifecycleTest {
    public static void main(String[] args) {
        stateMachineRejectsStaleAndRacingCommands();
        pendingResultRejectsRestartUntilCleared();
        startupCancellationFencesLateNativeSuccess();
        failureClaimsCleanupFromEveryLivePhase();
        publicationSerializesAConcurrentDiscard();
        rejectedServiceCommandsStopOnlyWithoutAnOwner();
        foregroundTypesFollowApiBuckets();
        visibleMicrophonePermissionAcceptsOnlyUsableAppOps();
        transientResultIsStagedBeforeCommitAndExpiresWithoutExtension();
        committedResultIsConsumedExactlyOnce();
        notificationFormsAreContentFreeAndStateExact();
    }

    private static void stateMachineRejectsStaleAndRacingCommands() {
        ForegroundRecordingCoordinator c = new ForegroundRecordingCoordinator();
        ForegroundRecordingCoordinator.Session s = c.begin();
        assertTrue(s != null, "first start accepted");
        assertTrue(c.begin() == null, "concurrent start rejected without disturbing owner");
        assertEquals(s.generation, c.session().generation, "concurrent start preserves current session");
        assertEquals(ForegroundRecordingCoordinator.DiscardResult.REJECTED,
                c.discard("stale-token", s.generation),
                "stale discard cannot disturb the active owner");
        assertEquals(s.generation, c.session().generation,
                "stale command preserves the active generation");
        assertFalse(c.stopForTranscription(s.token, s.generation), "transcribe rejected while starting");
        assertEquals(ForegroundRecordingCoordinator.State.STARTING, c.state(),
                "state-inapplicable transcribe leaves start untouched");
        assertTrue(c.nativeStarted(s.generation, s.attempt), "current native success accepted");
        assertEquals(ForegroundRecordingCoordinator.State.STARTING, c.state(),
                "native success is private until recording notification confirmation");
        assertTrue(c.confirmRecording(s.generation), "notification confirmation publishes recording state");
        assertTrue(c.stopForTranscription(s.token, s.generation), "first terminal command wins");
        assertEquals(ForegroundRecordingCoordinator.DiscardResult.REJECTED,
                c.discard(s.token, s.generation), "racing terminal command rejected");
        assertEquals(ForegroundRecordingCoordinator.State.STOPPING_FOR_TRANSCRIPTION,
                c.state(), "state remains claimed by first command");
    }

    private static void pendingResultRejectsRestartUntilCleared() {
        ForegroundRecordingCoordinator c = new ForegroundRecordingCoordinator();
        ForegroundRecordingCoordinator.Session first = c.begin();
        assertTrue(c.nativeStarted(first.generation, first.attempt), "first capture starts");
        assertTrue(c.confirmRecording(first.generation), "first notification confirms recording");
        assertTrue(c.stopForTranscription(first.token, first.generation), "first capture stops");
        assertTrue(c.captureEnded(first.generation), "first capture reaches inference");
        TransientResultVault vault = TransientResultVault.global();
        assertTrue(vault.stage("pending transcript", first.token, first.generation,
                first.notificationNonce, System.currentTimeMillis() + 60_000L), "result stages");
        assertTrue(vault.commit(first.token, first.generation, first.notificationNonce),
                "result commits");
        assertTrue(c.resultReady(first.generation), "first result becomes ready");
        c.finishService(first.generation);
        assertTrue(c.begin() == null, "pending result rejects a new recording");
        assertEquals("pending transcript",
                vault.read(first.token, first.generation, first.notificationNonce).text,
                "rejected start preserves pending transcript");
        assertTrue(vault.clear(first.token, first.generation, first.notificationNonce),
                "explicit clear removes pending result");
        ForegroundRecordingCoordinator.Session second = c.begin();
        assertTrue(second != null, "new recording is allowed only after result clears");
        assertTrue(second.generation > first.generation, "new recording advances generation");
    }

    private static void startupCancellationFencesLateNativeSuccess() {
        ForegroundRecordingCoordinator c = new ForegroundRecordingCoordinator();
        ForegroundRecordingCoordinator.Session s = c.begin();
        assertEquals(ForegroundRecordingCoordinator.DiscardResult.CLAIMED,
                c.discard(s.token, s.generation), "cancel start accepted");
        assertTrue(c.startCancelled(s.generation, s.attempt), "native gate observes cancellation");
        assertFalse(c.nativeStarted(s.generation, s.attempt), "late native success rejected");
        assertEquals(ForegroundRecordingCoordinator.State.DISCARDING, c.state(), "never records after cancel");
        c.finishDiscard(s.generation);
        assertEquals(ForegroundRecordingCoordinator.State.IDLE, c.state(), "discard returns idle");
    }

    private static void failureClaimsCleanupFromEveryLivePhase() {
        ForegroundRecordingCoordinator starting = new ForegroundRecordingCoordinator();
        ForegroundRecordingCoordinator.Session start = starting.begin();
        assertTrue(starting.fail(start.generation), "start timeout claims cleanup");
        assertTrue(starting.startCancelled(start.generation, start.attempt),
                "failed start latches native cancellation");
        assertFalse(starting.fail(start.generation), "duplicate cleanup is rejected");
        starting.finishDiscard(start.generation);
        assertEquals(ForegroundRecordingCoordinator.State.IDLE, starting.state(),
                "failed start cleanup returns idle");

        ForegroundRecordingCoordinator recording = recordingCoordinator();
        ForegroundRecordingCoordinator.Session capture = recording.session();
        assertTrue(recording.fail(capture.generation), "capture watchdog claims cleanup");
        recording.finishDiscard(capture.generation);
        assertEquals(ForegroundRecordingCoordinator.State.IDLE, recording.state(),
                "capture failure cleanup returns idle");

        ForegroundRecordingCoordinator inference = recordingCoordinator();
        ForegroundRecordingCoordinator.Session transcribing = inference.session();
        assertTrue(inference.stopForTranscription(transcribing.token, transcribing.generation),
                "capture stop accepted");
        assertTrue(inference.captureEnded(transcribing.generation), "inference begins");
        assertTrue(inference.fail(transcribing.generation), "inference timeout claims cleanup");
        inference.finishDiscard(transcribing.generation);
        assertEquals(ForegroundRecordingCoordinator.State.IDLE, inference.state(),
                "inference failure cleanup returns idle");
    }

    private static void publicationSerializesAConcurrentDiscard() {
        ForegroundRecordingCoordinator coordinator = recordingCoordinator();
        ForegroundRecordingCoordinator.Session session = coordinator.session();
        assertTrue(coordinator.stopForTranscription(session.token, session.generation),
                "capture stop accepted");
        assertTrue(coordinator.captureEnded(session.generation), "inference begins");
        assertTrue(coordinator.beginPublication(session.generation), "publication claims transaction");
        assertEquals(ForegroundRecordingCoordinator.DiscardResult.DEFERRED,
                coordinator.discard(session.token, session.generation),
                "discard waits while result publication is in flight");
        assertTrue(coordinator.resultReady(session.generation), "result commit changes state");
        assertTrue(coordinator.finishPublication(session.generation),
                "deferred discard is delivered after atomic publication");
    }

    private static ForegroundRecordingCoordinator recordingCoordinator() {
        ForegroundRecordingCoordinator coordinator = new ForegroundRecordingCoordinator();
        ForegroundRecordingCoordinator.Session session = coordinator.begin();
        assertTrue(coordinator.nativeStarted(session.generation, session.attempt),
                "native capture starts");
        assertTrue(coordinator.confirmRecording(session.generation),
                "recording notification confirms");
        return coordinator;
    }

    private static void rejectedServiceCommandsStopOnlyWithoutAnOwner() {
        ForegroundRecordingCoordinator coordinator = new ForegroundRecordingCoordinator();
        assertTrue(ForegroundServiceStopPolicy.shouldStop(coordinator),
                "a stale command may stop an ownerless service instance");
        ForegroundRecordingCoordinator.Session owner = coordinator.begin();
        assertFalse(ForegroundServiceStopPolicy.shouldStop(coordinator),
                "duplicate, stale, null, and malformed commands preserve a live owner");
        assertEquals(owner.generation, coordinator.session().generation,
                "checking the stop policy cannot mutate the owner");
    }

    private static void foregroundTypesFollowApiBuckets() {
        assertEquals(ForegroundTypePolicy.UNTYPED, ForegroundTypePolicy.captureType(28), "api 28 capture");
        assertEquals(ForegroundTypePolicy.UNTYPED, ForegroundTypePolicy.inferenceType(28), "api 28 inference");
        assertEquals(ForegroundTypePolicy.MICROPHONE, ForegroundTypePolicy.captureType(29), "api 29 capture");
        assertEquals(ForegroundTypePolicy.NONE, ForegroundTypePolicy.inferenceType(33), "api 33 inference");
        assertEquals(ForegroundTypePolicy.MICROPHONE, ForegroundTypePolicy.captureType(35), "api 35 capture");
        assertEquals(ForegroundTypePolicy.SHORT_SERVICE, ForegroundTypePolicy.inferenceType(34), "api 34 inference");
        assertEquals(ForegroundTypePolicy.SHORT_SERVICE,
                ForegroundTypePolicy.inferenceType(35), "api 35 inference");
    }

    private static void visibleMicrophonePermissionAcceptsOnlyUsableAppOps() {
        assertTrue(MicrophonePermissionPolicy.allowsVisibleStart(true, 0, true),
                "allowed app-op permits a visible start");
        assertTrue(MicrophonePermissionPolicy.allowsVisibleStart(true, 4, true),
                "foreground app-op permits a resumed activity start");
        assertFalse(MicrophonePermissionPolicy.allowsVisibleStart(true, 4, false),
                "foreground app-op is rejected without the visible activity");
        assertFalse(MicrophonePermissionPolicy.allowsVisibleStart(false, 0, true),
                "runtime denial always rejects capture");
        assertFalse(MicrophonePermissionPolicy.allowsVisibleStart(true, 1, true),
                "ignored app-op fails closed");
        assertFalse(MicrophonePermissionPolicy.allowsVisibleStart(true, 2, true),
                "errored app-op fails closed");
    }

    private static void transientResultIsStagedBeforeCommitAndExpiresWithoutExtension() {
        AtomicLong now = new AtomicLong(1_000L);
        TransientResultVault vault = new TransientResultVault(now::get);
        vault.stage("secret words", "token", 7, "nonce", 2_000L);
        assertTrue(vault.read("token", 7, "nonce") == null, "staging slot is unreadable");
        assertTrue(vault.isStaged("token", 7, "nonce"),
                "matching review launch can wait for atomic publication");
        assertFalse(vault.isStaged("token", 7, "wrong"),
                "staged publication remains nonce scoped");
        assertTrue(vault.commit("token", 7, "nonce"), "matching stage commits");
        assertEquals("secret words", vault.read("token", 7, "nonce").text, "committed transcript readable");
        now.set(1_500L);
        assertFalse(vault.stage("replacement", "new", 8, "nonce2", 9_000L), "pending result is exclusive");
        assertEquals(2_000L, vault.read("token", 7, "nonce").expiresAtMillis, "expiry is not extended");
        now.set(2_001L);
        assertTrue(vault.expire("token", 7, "nonce"),
                "scheduled expiry clears the exact committed transcript");
        assertTrue(vault.read("token", 7, "nonce") == null, "expired transcript is unreachable");

        AtomicLong lazyNow = new AtomicLong(10_000L);
        TransientResultVault lazy = new TransientResultVault(lazyNow::get);
        assertTrue(lazy.stage("private", "token", 8, "nonce", 11_000L), "lazy result stages");
        assertTrue(lazy.commit("token", 8, "nonce"), "lazy result commits");
        lazyNow.set(11_001L);
        assertTrue(lazy.read("token", 8, "nonce") == null,
                "read fails closed after TTL even when scheduler was delayed");
    }

    private static void committedResultIsConsumedExactlyOnce() {
        AtomicLong now = new AtomicLong(1_000L);
        TransientResultVault vault = new TransientResultVault(now::get);
        assertTrue(vault.stage("copy me", "token", 8, "nonce", 2_000L), "stage succeeds");
        assertTrue(vault.commit("token", 8, "nonce"), "commit succeeds");
        assertTrue(vault.consume("token", 8, "wrong") == null, "wrong nonce cannot consume result");
        TransientResultVault.Entry consumed = vault.consume("token", 8, "nonce");
        assertEquals("copy me", consumed.text, "explicit commit receives transcript");
        assertTrue(vault.consume("token", 8, "nonce") == null, "result is consumed exactly once");
        assertTrue(vault.read("token", 8, "nonce") == null, "consumed result is no longer readable");
    }

    private static void notificationFormsAreContentFreeAndStateExact() {
        NotificationContract.Form starting = NotificationContract.forState(
                ForegroundRecordingCoordinator.State.STARTING);
        assertEquals("Preparing recording", starting.title, "starting title");
        assertEquals("Microphone off • cancel to stop setup", starting.text, "starting text");
        assertEquals(1, starting.actions.length, "starting has one action");
        assertEquals(NotificationContract.CANCEL_START, starting.actions[0], "starting can only cancel");
        assertEquals("Cancel start", NotificationContract.labelFor(starting.actions[0]),
                "starting action label is exact");
        NotificationContract.Form recording = NotificationContract.forState(
                ForegroundRecordingCoordinator.State.RECORDING);
        assertEquals(2, recording.actions.length, "recording has two actions");
        assertEquals("Stop and discard", NotificationContract.labelFor(recording.actions[0]),
                "discard label is exact");
        assertEquals("Stop and transcribe", NotificationContract.labelFor(recording.actions[1]),
                "transcribe label is exact");
        NotificationContract.Form transcribing = NotificationContract.forState(
                ForegroundRecordingCoordinator.State.TRANSCRIBING);
        assertEquals("Transcribing — microphone off", transcribing.title, "canonical mic-off title");
        assertEquals("Processing on device", transcribing.text, "canonical processing text");
        assertEquals(NotificationContract.DISCARD, transcribing.actions[0], "transcribing only discards");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        assertTrue(!value, message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
