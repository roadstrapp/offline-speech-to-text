package dev.notune.transcribe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CustomTermsStoreTest {
    public static void main(String[] args) throws Exception {
        normalizationIsStableAndCaseInsensitiveDuplicatesAreRemoved();
        unsafeOrOversizedListsAreRejected();
        writesAreNormalizedAtomicAndClearable();
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
        assertProblem(CustomTerms.Problem.TOO_MANY_TERMS, numberedTerms(33));
        assertProblem(CustomTerms.Problem.PROMPT_TOO_LONG,
                repeat("a", 70) + "\n" + repeat("b", 70) + "\n" + repeat("c", 70));
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
