package dev.notune.transcribe;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    static final int MAX_TERMS = 128;
    static final int MAX_TERM_CODE_POINTS = 80;
    // Whisper exposes a 223-token previous-context window. A byte-level BPE
    // cannot require more tokens than input bytes, so this leaves headroom for
    // Whisper's prompt marker while remaining deterministic without shipping
    // a second tokenizer in the Android UI process.
    static final int MAX_PROMPT_UTF8_BYTES = 200;
    private static final int MAX_FILE_BYTES = 65536;

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
            String term = DictionaryText.normalize(line);
            if (term.isEmpty()) continue;
            if (term.contains("<|") || term.contains("|>")) {
                throw new InvalidTermsException(Problem.SPECIAL_TOKEN);
            }
            for (int offset = 0; offset < term.length();) {
                int codePoint = term.codePointAt(offset);
                if (Character.isISOControl(codePoint) || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
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

        String stored = String.join("\n", terms);
        if (!stored.isEmpty()) stored += "\n";
        if (stored.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
            throw new InvalidTermsException(Problem.PROMPT_TOO_LONG);
        }
        // Whisper has a smaller prompt budget than the dictionary. Send a
        // complete prefix of terms; Parakeet uses the entire list locally.
        StringBuilder prompt = new StringBuilder();
        int promptBytes = 0;
        for (String term : terms) {
            int separatorBytes = prompt.length() == 0 ? 0 : 2;
            int nextBytes = promptBytes + separatorBytes
                    + term.getBytes(StandardCharsets.UTF_8).length;
            if (nextBytes > MAX_PROMPT_UTF8_BYTES) break;
            if (separatorBytes != 0) prompt.append(", ");
            prompt.append(term);
            promptBytes = nextBytes;
        }
        return new Parsed(terms, stored, prompt.toString());
    }

    static Parsed read(File filesDir) throws IOException, InvalidTermsException {
        File file = new File(filesDir, FILE_NAME);
        if (!file.isFile()) return parse("");
        if (file.length() > MAX_FILE_BYTES) {
            throw new InvalidTermsException(Problem.PROMPT_TOO_LONG);
        }
        return parse(DictionaryFiles.read(file, MAX_FILE_BYTES));
    }

    static Parsed write(File filesDir, String raw) throws IOException, InvalidTermsException {
        Parsed parsed = parse(raw);
        DictionaryFiles.write(filesDir, FILE_NAME, parsed.storedText);
        return parsed;
    }
}
