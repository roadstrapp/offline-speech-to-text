package dev.notune.transcribe;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounded, app-private exact corrections applied after recognition. */
final class PersonalCorrections {
    static final String FILE_NAME = "personal_corrections";
    static final String ENABLED_FILE_NAME = "personal_corrections_enabled";
    static final int MAX_RULES = 32;
    static final int MAX_SIDE_CODE_POINTS = 80;
    private static final int MAX_FILE_BYTES = 8192;

    enum Problem {
        TOO_MANY_RULES,
        MISSING_SEPARATOR,
        EMPTY_SIDE,
        SIDE_TOO_LONG,
        INVALID_CONTROL,
        AMBIGUOUS_SOURCE,
        NUMBER_MISMATCH
    }

    static final class InvalidCorrectionsException extends Exception {
        final Problem problem;

        InvalidCorrectionsException(Problem problem) {
            super(problem.name());
            this.problem = problem;
        }
    }

    static final class Rule {
        final String heard;
        final String written;

        Rule(String heard, String written) {
            this.heard = heard;
            this.written = written;
        }
    }

    static final class Parsed {
        final List<Rule> rules;
        final String storedText;

        Parsed(List<Rule> rules, String storedText) {
            this.rules = Collections.unmodifiableList(rules);
            this.storedText = storedText;
        }
    }

    private PersonalCorrections() {}

    static Parsed parse(String raw) throws InvalidCorrectionsException {
        String input = raw == null ? "" : raw;
        List<Rule> pairs = new ArrayList<>();
        for (String line : input.split("\\r\\n|\\n|\\r", -1)) {
            if (containsUnsafeCharacter(line)) {
                throw new InvalidCorrectionsException(Problem.INVALID_CONTROL);
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int separator = trimmed.indexOf("=>");
            if (separator < 0 || trimmed.indexOf("=>", separator + 2) >= 0) {
                throw new InvalidCorrectionsException(Problem.MISSING_SEPARATOR);
            }
            pairs.add(new Rule(
                    trimmed.substring(0, separator),
                    trimmed.substring(separator + 2)));
        }
        return fromPairs(pairs);
    }

    /** Validates UI-owned pairs without exposing the on-disk separator to users. */
    static Parsed fromPairs(List<Rule> pairs) throws InvalidCorrectionsException {
        List<Rule> rules = new ArrayList<>();
        Set<String> sources = new HashSet<>();
        List<Rule> input = pairs == null ? Collections.emptyList() : pairs;
        if (input.size() > MAX_RULES) {
            throw new InvalidCorrectionsException(Problem.TOO_MANY_RULES);
        }
        for (Rule pair : input) {
            if (pair == null) {
                throw new InvalidCorrectionsException(Problem.EMPTY_SIDE);
            }
            String heard = DictionaryText.normalize(pair.heard);
            String written = DictionaryText.normalize(pair.written);
            if (heard.isEmpty() || written.isEmpty()) {
                throw new InvalidCorrectionsException(Problem.EMPTY_SIDE);
            }
            if (heard.contains("=>") || written.contains("=>")) {
                throw new InvalidCorrectionsException(Problem.MISSING_SEPARATOR);
            }
            if (heard.codePointCount(0, heard.length()) > MAX_SIDE_CODE_POINTS
                    || written.codePointCount(0, written.length()) > MAX_SIDE_CODE_POINTS) {
                throw new InvalidCorrectionsException(Problem.SIDE_TOO_LONG);
            }
            if (containsUnsafeCharacter(heard) || containsUnsafeCharacter(written)) {
                throw new InvalidCorrectionsException(Problem.INVALID_CONTROL);
            }
            String sourceKey = heard.toLowerCase(Locale.ROOT);
            if (!sources.add(sourceKey)) {
                throw new InvalidCorrectionsException(Problem.AMBIGUOUS_SOURCE);
            }
            if (!digits(heard).equals(digits(written))) {
                throw new InvalidCorrectionsException(Problem.NUMBER_MISMATCH);
            }
            rules.add(new Rule(heard, written));
        }

        StringBuilder stored = new StringBuilder();
        for (Rule rule : rules) {
            stored.append(rule.heard).append(" => ").append(rule.written).append('\n');
        }
        String storedText = stored.toString();
        if (storedText.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
            throw new InvalidCorrectionsException(Problem.SIDE_TOO_LONG);
        }
        return new Parsed(rules, storedText);
    }

    static Parsed read(File filesDir) throws IOException, InvalidCorrectionsException {
        File file = new File(filesDir, FILE_NAME);
        if (!file.isFile()) return parse("");
        if (file.length() > MAX_FILE_BYTES) {
            throw new InvalidCorrectionsException(Problem.SIDE_TOO_LONG);
        }
        return parse(DictionaryFiles.read(file, MAX_FILE_BYTES));
    }

    static Parsed write(File filesDir, String raw)
            throws IOException, InvalidCorrectionsException {
        Parsed parsed = parse(raw);
        DictionaryFiles.write(filesDir, FILE_NAME, parsed.storedText);
        return parsed;
    }

    static Parsed writePairs(File filesDir, List<Rule> pairs)
            throws IOException, InvalidCorrectionsException {
        Parsed parsed = fromPairs(pairs);
        DictionaryFiles.write(filesDir, FILE_NAME, parsed.storedText);
        return parsed;
    }

    static boolean isEnabled(File filesDir) {
        return new File(filesDir, ENABLED_FILE_NAME).isFile();
    }

    /** Removes stale opt-in state when no valid rule can actually run. */
    static boolean reconcileEnabled(File filesDir) throws IOException {
        if (!isEnabled(filesDir)) return false;
        try {
            if (!read(filesDir).rules.isEmpty()) return true;
        } catch (InvalidCorrectionsException | CharacterCodingException ignored) {
            // Native parsing also rejects the complete file. Keep UI and
            // runtime state aligned without exposing rule content.
        }
        setEnabled(filesDir, false);
        return false;
    }

    static void setEnabled(File filesDir, boolean enabled) throws IOException {
        if (enabled) {
            DictionaryFiles.write(filesDir, ENABLED_FILE_NAME, "1\n");
        } else {
            Files.deleteIfExists(new File(filesDir, ENABLED_FILE_NAME).toPath());
        }
    }

    private static boolean containsUnsafeCharacter(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if ((codePoint >= 0xD800 && codePoint <= 0xDFFF) || Character.isISOControl(codePoint)
                    || codePoint == '"' || codePoint == '\''
                    || codePoint == 0x2018 || codePoint == 0x2019
                    || codePoint == 0x201C || codePoint == 0x201D) {
                return true;
            }
        }
        return false;
    }

    private static String digits(String value) {
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isDigit(codePoint)) result.appendCodePoint(codePoint);
        }
        return result.toString();
    }
}
