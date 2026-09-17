package dev.notune.transcribe;

import java.util.function.LongSupplier;

/** Process-memory-only transcript vault. Uncommitted staging is never readable. */
final class TransientResultVault {
    static final long TTL_MILLIS = 10 * 60 * 1000L;
    private static final TransientResultVault GLOBAL = new TransientResultVault(System::currentTimeMillis);

    static final class Entry {
        final String text;
        final String token;
        final long generation;
        final String nonce;
        final long expiresAtMillis;
        Entry(String text, String token, long generation, String nonce, long expiresAtMillis) {
            this.text = text;
            this.token = token;
            this.generation = generation;
            this.nonce = nonce;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private final LongSupplier clock;
    private Entry staged;
    private Entry committed;

    TransientResultVault(LongSupplier clock) { this.clock = clock; }
    static TransientResultVault global() { return GLOBAL; }

    synchronized boolean stage(String text, String token, long generation, String nonce,
                               long expiresAtMillis) {
        expireIfNeeded();
        if (committed != null || staged != null || text == null || text.trim().isEmpty()
                || nonce == null || nonce.isEmpty()) return false;
        staged = new Entry(text, token, generation, nonce, expiresAtMillis);
        return true;
    }

    synchronized boolean commit(String token, long generation, String nonce) {
        if (staged == null || !matches(staged, token, generation, nonce)
                || staged.expiresAtMillis <= clock.getAsLong()) {
            staged = null;
            return false;
        }
        committed = staged;
        staged = null;
        return true;
    }

    synchronized Entry read(String token, long generation, String nonce) {
        expireIfNeeded();
        return committed != null && matches(committed, token, generation, nonce) ? committed : null;
    }

    synchronized Entry peek() {
        expireIfNeeded();
        return committed;
    }

    synchronized boolean isStaged(String token, long generation, String nonce) {
        expireIfNeeded();
        return staged != null && matches(staged, token, generation, nonce);
    }

    synchronized Entry consume(String token, long generation, String nonce) {
        expireIfNeeded();
        if (committed == null || !matches(committed, token, generation, nonce)) return null;
        Entry result = committed;
        committed = null;
        staged = null;
        return result;
    }

    synchronized boolean clear(String token, long generation, String nonce) {
        expireIfNeeded();
        if (committed == null || !matches(committed, token, generation, nonce)) return false;
        committed = null;
        staged = null;
        return true;
    }

    synchronized boolean expire(String token, long generation, String nonce) {
        long now = clock.getAsLong();
        if (committed != null && matches(committed, token, generation, nonce)
                && committed.expiresAtMillis <= now) {
            committed = null;
            staged = null;
            return true;
        }
        if (staged != null && matches(staged, token, generation, nonce)
                && staged.expiresAtMillis <= now) {
            staged = null;
            return true;
        }
        return false;
    }

    synchronized void rollback(String token, long generation, String nonce) {
        if (staged != null && matches(staged, token, generation, nonce)) staged = null;
    }

    private void expireIfNeeded() {
        if (committed != null && committed.expiresAtMillis <= clock.getAsLong()) committed = null;
        if (staged != null && staged.expiresAtMillis <= clock.getAsLong()) staged = null;
    }

    private static boolean matches(Entry entry, String token, long generation, String nonce) {
        return entry.generation == generation && entry.token.equals(token)
                && entry.nonce.equals(nonce);
    }
}
