package dev.notune.transcribe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PersonalCorrectionsStoreTest {
    public static void main(String[] args) throws Exception {
        rulesAreNormalizedAndExplicit();
        unsafeAmbiguousAndNumericChangesAreRejected();
        writesAndOptInAreAtomicAndClearable();
    }

    private static void rulesAreNormalizedAndExplicit() throws Exception {
        PersonalCorrections.Parsed parsed = PersonalCorrections.parse(
                "  marmot   protocol => Marmot Protocol  \n"
                        + "e\u0301clair => Éclair\n");
        assertEquals(2, parsed.rules.size(), "rule count");
        assertEquals("marmot protocol => Marmot Protocol\néclair => Éclair\n",
                parsed.storedText, "canonical stored form");
    }

    private static void unsafeAmbiguousAndNumericChangesAreRejected() throws Exception {
        assertProblem(PersonalCorrections.Problem.MISSING_SEPARATOR, "marmot protocol");
        assertProblem(PersonalCorrections.Problem.MISSING_SEPARATOR, "a => b => c");
        assertProblem(PersonalCorrections.Problem.EMPTY_SIDE, " => Marmot");
        assertProblem(PersonalCorrections.Problem.INVALID_CONTROL, "safe => unsafe\u0001");
        assertProblem(PersonalCorrections.Problem.INVALID_CONTROL, "quote => \"quote\"");
        assertProblem(PersonalCorrections.Problem.AMBIGUOUS_SOURCE,
                "marmot => Marmot\nMARMOT => another");
        assertProblem(PersonalCorrections.Problem.NUMBER_MISMATCH, "nip 46 => NIP 47");
        assertProblem(PersonalCorrections.Problem.SIDE_TOO_LONG,
                repeat("x", 81) + " => short");
        assertProblem(PersonalCorrections.Problem.TOO_MANY_RULES, numberedRules(33));
    }

    private static void writesAndOptInAreAtomicAndClearable() throws Exception {
        Path root = Files.createTempDirectory("ostt-personal-corrections-java");
        PersonalCorrections.write(root.toFile(), "ipf => IPF\n");
        Path file = root.resolve(PersonalCorrections.FILE_NAME);
        assertEquals("ipf => IPF\n",
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8),
                "write publishes normalized complete data");
        assertEquals(false, PersonalCorrections.isEnabled(root.toFile()),
                "corrections default off");
        PersonalCorrections.setEnabled(root.toFile(), true);
        assertEquals(true, PersonalCorrections.isEnabled(root.toFile()),
                "explicit marker enables corrections");
        assertEquals(true, PersonalCorrections.reconcileEnabled(root.toFile()),
                "valid rule keeps explicit opt-in");
        PersonalCorrections.setEnabled(root.toFile(), false);
        assertEquals(false, PersonalCorrections.isEnabled(root.toFile()),
                "disable removes marker");
        PersonalCorrections.write(root.toFile(), "");
        assertEquals(false, Files.exists(file), "empty list removes private state");
        PersonalCorrections.setEnabled(root.toFile(), true);
        assertEquals(false, PersonalCorrections.reconcileEnabled(root.toFile()),
                "empty dictionary repairs stale opt-in");
        assertEquals(false, PersonalCorrections.isEnabled(root.toFile()),
                "stale opt-in marker is removed");
        Files.delete(root);
    }

    private static void assertProblem(PersonalCorrections.Problem expected, String value)
            throws Exception {
        try {
            PersonalCorrections.parse(value);
            throw new AssertionError("expected " + expected);
        } catch (PersonalCorrections.InvalidCorrectionsException error) {
            assertEquals(expected, error.problem, "validation problem");
        }
    }

    private static String numberedRules(int count) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < count; index++) {
            result.append("term").append(index).append(" => term").append(index).append('\n');
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
