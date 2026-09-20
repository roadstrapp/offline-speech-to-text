package dev.notune.transcribe;

import java.io.IOException;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class PendingTranscriptRecordTest {
    public static void main(String[] args) throws Exception {
        exactUnicodeTextRoundTrips();
        checksumRejectsTampering();
        emptyTextIsRejected();
        emergencyBufferNeverOverwritesUnrecoveredText();
        fileStoreNeverOverwritesAndCanClear();
        damagedRecordIsReplacedByExactText();
        concurrentStagingKeepsExactlyOneCompleteResult();
    }

    private static void exactUnicodeTextRoundTrips() throws Exception {
        String original = "First line\nZażółć 👩‍💻\n  trailing spaces  ";
        long savedAt = 1_726_000_123_456L;
        PendingTranscriptRecord.Entry decoded = PendingTranscriptRecord.decode(
                PendingTranscriptRecord.encode(original, savedAt));
        assertEquals(original, decoded.text, "recovery keeps every recognized character");
        assertEquals(savedAt, decoded.savedAtMillis, "recovery keeps its timestamp");
    }

    private static void checksumRejectsTampering() throws Exception {
        byte[] encoded = PendingTranscriptRecord.encode("do not lose this", 42L);
        byte[] tampered = Arrays.copyOf(encoded, encoded.length);
        tampered[24] ^= 1;
        assertThrows(() -> PendingTranscriptRecord.decode(tampered),
                "a damaged record must not be presented as exact text");
    }

    private static void emptyTextIsRejected() {
        assertThrows(() -> PendingTranscriptRecord.encode("  \n", 42L),
                "blank recognition results are not recovery records");
    }

    private static void emergencyBufferNeverOverwritesUnrecoveredText() {
        EmergencyTranscriptBuffer.clear();
        EmergencyTranscriptBuffer.putIfEmpty("first");
        EmergencyTranscriptBuffer.putIfEmpty("second");
        assertEquals("first", EmergencyTranscriptBuffer.peek(),
                "a later failure cannot overwrite an unrecovered result");
        EmergencyTranscriptBuffer.clear();
        assertEquals(null, EmergencyTranscriptBuffer.peek(), "clear removes emergency text");
    }

    private static void fileStoreNeverOverwritesAndCanClear() throws Exception {
        Path directory = Files.createTempDirectory("ostt-pending-record");
        assertEquals(true, PendingTranscriptFiles.saveIfEmpty(directory.toFile(), "first"),
                "an empty recovery slot accepts a result");
        assertEquals(false, PendingTranscriptFiles.saveIfEmpty(directory.toFile(), "second"),
                "a valid pending result is never overwritten");
        assertEquals("first", PendingTranscriptFiles.read(directory.toFile()).text,
                "the first exact result remains recoverable");
        assertEquals(true, PendingTranscriptFiles.clear(directory.toFile()),
                "explicit recovery clears the slot");
        assertEquals(false, PendingTranscriptFiles.hasRecord(directory.toFile()),
                "a cleared slot no longer blocks recording");
    }

    private static void damagedRecordIsReplacedByExactText() throws Exception {
        Path directory = Files.createTempDirectory("ostt-damaged-record");
        Files.write(PendingTranscriptFiles.recordFileForTest(directory.toFile()).toPath(),
                "partial".getBytes(StandardCharsets.UTF_8));
        assertEquals(true, PendingTranscriptFiles.saveIfEmpty(directory.toFile(), "replacement"),
                "an undecodable partial record cannot block a complete result");
        assertEquals("replacement", PendingTranscriptFiles.read(directory.toFile()).text,
                "the replacement remains exact");
    }

    private static void concurrentStagingKeepsExactlyOneCompleteResult() throws Exception {
        Path directory = Files.createTempDirectory("ostt-concurrent-record");
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger accepted = new AtomicInteger();
        for (String value : new String[] {"alpha", "beta"}) {
            new Thread(() -> {
                try {
                    start.await();
                    if (PendingTranscriptFiles.saveIfEmpty(directory.toFile(), value)) {
                        accepted.incrementAndGet();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();
        assertEquals(1, accepted.get(), "only one concurrent result claims the slot");
        String saved = PendingTranscriptFiles.read(directory.toFile()).text;
        assertEquals(true, saved.equals("alpha") || saved.equals("beta"),
                "the winner is a complete exact result");
    }

    private interface ThrowingAction { void run() throws Exception; }

    private static void assertThrows(ThrowingAction action, String message) {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        } catch (Exception unexpected) {
            throw new AssertionError(message + ": wrong exception", unexpected);
        }
        throw new AssertionError(message + ": no exception");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
