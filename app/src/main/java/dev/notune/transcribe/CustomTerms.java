package dev.notune.transcribe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * App-private custom terms shared by the main and IME processes.
 *
 * <p>The on-disk form is normalized UTF-8 with one term per line. Writes use
 * an atomic replacement so native transcription sees either the previous
 * complete list or the next complete list, never a partially written prompt.
 */
final class CustomTerms {
    static final String FILE_NAME = "custom_terms";
    static final int MAX_TERMS = 32;
    static final int MAX_TERM_CODE_POINTS = 80;
    // Whisper exposes a 223-token previous-context window. A byte-level BPE
    // cannot require more tokens than input bytes, so this leaves headroom for
    // Whisper's prompt marker while remaining deterministic without shipping
    // a second tokenizer in the Android UI process.
    static final int MAX_PROMPT_UTF8_BYTES = 200;
    private static final int MAX_FILE_BYTES = 4096;

    enum Problem {
        TOO_MANY_TERMS,
        TERM_TOO_LONG,
        INVALID_CONTROL,
        SPECIAL_TOKEN,
        PROMPT_TOO_LONG
    }

    static final class InvalidTermsException extends Exception {
        final Problem problem;

        InvalidTermsException(Problem problem) {
            super(problem.name());
            this.problem = problem;
        }
    }

    static final class Parsed {
        final List<String> terms;
        final String storedText;
        final String prompt;

        Parsed(List<String> terms, String storedText, String prompt) {
            this.terms = Collections.unmodifiableList(terms);
            this.storedText = storedText;
            this.prompt = prompt;
        }
    }

    private CustomTerms() {}

    static Parsed parse(String raw) throws InvalidTermsException {
        String input = raw == null ? "" : raw;
        String[] lines = input.split("\\r\\n|\\n|\\r", -1);
        List<String> terms = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : lines) {
            String term = normalizeWhitespace(Normalizer.normalize(
                    line.trim(), Normalizer.Form.NFC));
            if (term.isEmpty()) continue;
            if (term.contains("<|") || term.contains("|>")) {
                throw new InvalidTermsException(Problem.SPECIAL_TOKEN);
            }
            for (int offset = 0; offset < term.length();) {
                int codePoint = term.codePointAt(offset);
                if (Character.isISOControl(codePoint)) {
                    throw new InvalidTermsException(Problem.INVALID_CONTROL);
                }
                offset += Character.charCount(codePoint);
            }
            if (term.codePointCount(0, term.length()) > MAX_TERM_CODE_POINTS) {
                throw new InvalidTermsException(Problem.TERM_TOO_LONG);
            }
            String key = term.toLowerCase(Locale.ROOT);
            if (!seen.add(key)) continue;
            terms.add(term);
            if (terms.size() > MAX_TERMS) {
                throw new InvalidTermsException(Problem.TOO_MANY_TERMS);
            }
        }

        String prompt = String.join(", ", terms);
        if (prompt.getBytes(StandardCharsets.UTF_8).length > MAX_PROMPT_UTF8_BYTES) {
            throw new InvalidTermsException(Problem.PROMPT_TOO_LONG);
        }
        String stored = String.join("\n", terms);
        if (!stored.isEmpty()) stored += "\n";
        return new Parsed(terms, stored, prompt);
    }

    static Parsed read(File filesDir) throws IOException, InvalidTermsException {
        File file = new File(filesDir, FILE_NAME);
        if (!file.isFile()) return parse("");
        if (file.length() > MAX_FILE_BYTES) {
            throw new InvalidTermsException(Problem.PROMPT_TOO_LONG);
        }
        return parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    static Parsed write(File filesDir, String raw) throws IOException, InvalidTermsException {
        Parsed parsed = parse(raw);
        File target = new File(filesDir, FILE_NAME);
        File temporary = new File(filesDir, FILE_NAME + ".tmp");
        if (parsed.terms.isEmpty()) {
            Files.deleteIfExists(temporary.toPath());
            Files.deleteIfExists(target.toPath());
            return parsed;
        }

        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(parsed.storedText.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        return parsed;
    }

    private static String normalizeWhitespace(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = result.length() > 0;
            } else {
                if (pendingSpace) result.append(' ');
                result.appendCodePoint(codePoint);
                pendingSpace = false;
            }
        }
        return result.toString();
    }
}
