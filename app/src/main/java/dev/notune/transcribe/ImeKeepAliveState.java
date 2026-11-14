package dev.notune.transcribe;

import java.util.concurrent.atomic.AtomicLong;

/** Main-thread owner for one microphone foreground-service handoff. */
final class ImeKeepAliveState {
    private static final AtomicLong NEXT = new AtomicLong();
    private long token;
    private boolean ready;

    long begin() { ready = false; token = NEXT.incrementAndGet(); return token; }
    boolean matches(long value) { return value > 0 && token == value; }
    boolean acknowledge(long value) {
        if (!matches(value)) return false;
        ready = true;
        return true;
    }
    boolean ready() { return ready; }
    boolean pending() { return token != 0; }
    long token() { return token; }
    void finish() { token = 0; ready = false; }
}
