package dev.notune.transcribe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CustomTermsStoreTest {
    public static void main(String[] args) throws Exception {
        normalizationIsStableAndCaseInsensitiveDuplicatesAreRemoved();
        unsafeOrOversizedListsAreRejected();
        writesAreNormalizedAtomicAndClearable();
        corruptReadsPreserveBytes();
        concurrentSavesPublishCompleteFiles();
    }

    private static void normalizationIsStableAndCaseInsensitiveDuplicatesAreRemoved()
            throws Exception {
        CustomTerms.Parsed parsed = CustomTerms.parse(
                "  Nostr  \r\nnostr\nSatoshi   Nakamoto\ne\u0301clair\n");
        assertEquals(3, parsed.terms.size(), "case-insensitive duplicates collapse");
        assertEquals("Nostr, Satoshi Nakamoto, éclair", parsed.prompt,
                "prompt preserves first spelling and uses NFC");
        assertEquals("Nostr\nSatoshi Nakamoto\néclair\n", parsed.storedText,
                "stored form is canonical and newline terminated");
    }

    private static void unsafeOrOversizedListsAreRejected() throws Exception {
        assertProblem(CustomTerms.Problem.SPECIAL_TOKEN, "safe\n<|notimestamps|>");
        assertProblem(CustomTerms.Problem.INVALID_CONTROL, "safe\u0001unsafe");
        assertProblem(CustomTerms.Problem.TERM_TOO_LONG, repeat("x", 81));
        assertProblem(CustomTerms.Problem.TOO_MANY_TERMS, numberedTerms(129));
        CustomTerms.Parsed longList = CustomTerms.parse(
                repeat("a", 70) + "\n" + repeat("b", 70) + "\n" + repeat("c", 70));
        assertEquals(repeat("a", 70) + ", " + repeat("b", 70), longList.prompt,
                "Whisper uses a complete bounded prefix");
        assertEquals(3, longList.terms.size(), "Parakeet keeps all terms");
    }

    private static void writesAreNormalizedAtomicAndClearable() throws Exception {
        Path root = Files.createTempDirectory("ostt-custom-terms-java");
        CustomTerms.write(root.toFile(), "  HeliBoard \nNostr\n");
        Path file = root.resolve(CustomTerms.FILE_NAME);
        assertEquals("HeliBoard\nNostr\n",
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8),
                "write publishes only normalized complete data");
        assertEquals(false, Files.exists(root.resolve(CustomTerms.FILE_NAME + ".tmp")),
                "atomic staging file is not left behind");
        CustomTerms.write(root.toFile(), "\n");
        assertEquals(false, Files.exists(file), "empty list removes private state");
        Files.delete(root);
    }

    private static void corruptReadsPreserveBytes() throws Exception {
        Path root = Files.createTempDirectory("ostt-dictionary-corrupt");
        Path file = root.resolve(CustomTerms.FILE_NAME);
        byte[][] corrupt = { {(byte) 0xC3}, {(byte) 0xFF}, {(byte) 0xE2, (byte) 0x82} };
        for (byte[] bytes : corrupt) {
            Files.write(file, bytes);
            try {
                CustomTerms.read(root.toFile());
                throw new AssertionError("malformed UTF-8 accepted");
            } catch (java.io.IOException expected) {}
            if (!java.util.Arrays.equals(bytes, Files.readAllBytes(file))) {
                throw new AssertionError("corrupt bytes changed");
            }
        }
        Files.write(file, new byte[65537]);
        try {
            DictionaryFiles.read(file.toFile(), 65536);
            throw new AssertionError("overflow probe failed");
        } catch (java.io.IOException expected) {}
        assertProblem(CustomTerms.Problem.INVALID_CONTROL, "bad\uD800");
        Files.delete(file);
        Files.delete(root);
    }

    private static void concurrentSavesPublishCompleteFiles() throws Exception {
        Path root = Files.createTempDirectory("ostt-dictionary-concurrent");
        Path otherStaging = root.resolve(".dictionary-other.tmp");
        Files.write(otherStaging, new byte[] { 42 });
        CustomTerms.write(root.toFile(), "Initial");
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.List<Thread> writers = new java.util.ArrayList<>();
        for (int number = 0; number < 4; number++) {
            final String value = "Writer" + number;
            Thread writer = new Thread(() -> {
                try {
                    for (int turn = 0; turn < 20; turn++) {
                        CustomTerms.write(root.toFile(), value + "\nComplete");
                        CustomTerms.Parsed read = CustomTerms.read(root.toFile());
                        if (read.terms.size() != 2 || !read.terms.get(1).equals("Complete")) {
                            throw new AssertionError("partial publication observed");
                        }
                    }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            });
            writers.add(writer);
            writer.start();
        }
        for (Thread writer : writers) writer.join();
        if (failure.get() != null) throw new AssertionError("concurrent save failed", failure.get());
        assertEquals(42, (int) Files.readAllBytes(otherStaging)[0], "other writer staging retained");
        try (java.util.stream.Stream<Path> files = Files.list(root)) {
            assertEquals(2L, files.count(), "only final file and unrelated staging remain");
        }
        CustomTerms.write(root.toFile(), "");
        Files.delete(otherStaging);
        Files.delete(root);
    }

    private static void assertProblem(CustomTerms.Problem expected, String value)
            throws Exception {
        try {
            CustomTerms.parse(value);
            throw new AssertionError("expected " + expected);
        } catch (CustomTerms.InvalidTermsException error) {
            assertEquals(expected, error.problem, "validation problem");
        }
    }

    private static String numberedTerms(int count) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < count; index++) {
            result.append("term").append(index).append('\n');
        }
        return result.toString();
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
