package dev.notune.transcribe;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

/** Content-free, generation-scoped state machine for the long recording owner. */
final class ForegroundRecordingCoordinator {
    enum State { IDLE, STARTING, RECORDING, STOPPING_FOR_TRANSCRIPTION,
        TRANSCRIBING, RESULT_READY, DISCARDING, FAILED }
    enum DiscardResult { REJECTED, CLAIMED, DEFERRED }

    static final class Session {
        final String token;
        final long generation;
        final long attempt;
        final String notificationNonce;
        final long startedAtMillis;
        Session(String token, long generation, long attempt, String notificationNonce,
                long startedAtMillis) {
            this.token = token;
            this.generation = generation;
            this.attempt = attempt;
            this.notificationNonce = notificationNonce;
            this.startedAtMillis = startedAtMillis;
        }
    }

    private static final AtomicLong NEXT_GENERATION = new AtomicLong();
    private final SecureRandom random = new SecureRandom();
    private State state = State.IDLE;
    private Session session;
    private boolean startCancelled;
    private boolean nativeReady;
    private boolean publicationInFlight;
    private boolean discardAfterPublication;

    synchronized Session begin() {
        if (state == State.RESULT_READY && TransientResultVault.global().peek() == null) reset();
        if (state != State.IDLE || TransientResultVault.global().peek() != null) return null;
        session = new Session(random128(), NEXT_GENERATION.incrementAndGet(),
                random.nextLong(), random128(), System.currentTimeMillis());
        startCancelled = false;
        nativeReady = false;
        publicationInFlight = false;
        discardAfterPublication = false;
        state = State.STARTING;
        return session;
    }

    synchronized State state() { return state; }
    synchronized Session session() { return session; }

    synchronized boolean matches(String token, long generation) {
        return session != null && session.generation == generation
                && session.token.equals(token);
    }

    synchronized boolean startCancelled(long generation, long attempt) {
        return session != null && session.generation == generation && session.attempt == attempt
                && startCancelled;
    }

    synchronized boolean nativeStarted(long generation, long attempt) {
        if (state != State.STARTING || startCancelled || session == null
                || session.generation != generation || session.attempt != attempt) return false;
        nativeReady = true;
        return true;
    }

    synchronized boolean confirmRecording(long generation) {
        if (state != State.STARTING || !nativeReady || startCancelled || session == null
                || session.generation != generation) return false;
        state = State.RECORDING;
        return true;
    }

    synchronized DiscardResult discard(String token, long generation) {
        if (!matches(token, generation)) return DiscardResult.REJECTED;
        if (publicationInFlight) {
            discardAfterPublication = true;
            return DiscardResult.DEFERRED;
        }
        if (state != State.STARTING && state != State.RECORDING && state != State.TRANSCRIBING) {
            return DiscardResult.REJECTED;
        }
        if (state == State.STARTING) startCancelled = true;
        state = State.DISCARDING;
        return DiscardResult.CLAIMED;
    }

    synchronized boolean stopForTranscription(String token, long generation) {
        if (!matches(token, generation) || state != State.RECORDING) return false;
        state = State.STOPPING_FOR_TRANSCRIPTION;
        return true;
    }

    synchronized boolean captureEnded(long generation) {
        if (session == null || session.generation != generation
                || state != State.STOPPING_FOR_TRANSCRIPTION) return false;
        state = State.TRANSCRIBING;
        return true;
    }

    synchronized boolean resultReady(long generation) {
        if (session == null || session.generation != generation || state != State.TRANSCRIBING) return false;
        state = State.RESULT_READY;
        return true;
    }

    synchronized boolean fail(long generation) {
        if (session == null || session.generation != generation
                || state == State.DISCARDING || state == State.FAILED
                || state == State.RESULT_READY) return false;
        startCancelled = true;
        publicationInFlight = false;
        discardAfterPublication = false;
        state = State.FAILED;
        return true;
    }

    synchronized boolean beginPublication(long generation) {
        if (session == null || session.generation != generation
                || state != State.TRANSCRIBING || publicationInFlight) return false;
        publicationInFlight = true;
        return true;
    }

    synchronized boolean finishPublication(long generation) {
        if (session == null || session.generation != generation || !publicationInFlight) {
            return false;
        }
        publicationInFlight = false;
        boolean deferred = discardAfterPublication;
        discardAfterPublication = false;
        return deferred;
    }

    synchronized void finishDiscard(long generation) {
        if (session != null && session.generation == generation
                && (state == State.DISCARDING || state == State.FAILED)) reset();
    }

    synchronized void finishService(long generation) {
        if (session != null && session.generation == generation && state == State.RESULT_READY) reset();
    }

    private void reset() {
        state = State.IDLE;
        session = null;
        startCancelled = false;
        nativeReady = false;
        publicationInFlight = false;
        discardAfterPublication = false;
    }

    private String random128() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder out = new StringBuilder(32);
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }
}
