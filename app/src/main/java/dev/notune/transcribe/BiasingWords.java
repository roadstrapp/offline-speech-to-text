package dev.notune.transcribe;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Words another app sends with one recognition request
 * ({@code RecognizerIntent.EXTRA_BIASING_STRINGS}).
 *
 * <p>They join the user's own preferred words for that request only. Nothing here or
 * downstream stores, shows, logs or returns them: the cleaned list goes straight to the
 * native job of the request and is dropped with it. Native code applies the same bounds
 * again ({@code src/custom_terms.rs}).
 */
final class BiasingWords {
    /** {@code RecognizerIntent.EXTRA_BIASING_STRINGS}: public from API 33, read on every version. */
    static final String EXTRA = "android.speech.extra.BIASING_STRINGS";
    /** Service meta-data telling callers how many words a request may carry. */
    static final String CAPABILITY = "app.offlinespeechtotext.capability.BIASING_STRINGS_MAX_COUNT";

    /** Words accepted from one request, in the caller's order (its priority). */
    static final int MAX_WORDS = 1000;
    /** UTF-8 bytes of all accepted words together. */
    static final int MAX_TOTAL_BYTES = 16 * 1024;
    /** Entries examined; a list of mostly unusable entries is not searched to its end. */
    static final int MAX_EXAMINED = 4 * MAX_WORDS;

    private BiasingWords() {}

    /**
     * The usable words of a request, one per line, or null when there are none. An entry
     * that is not a string, is empty, is longer than a preferred word may be, or contains a
     * control character or a model control token is dropped alone; the rest is kept in order.
     */
    static String sanitize(List<?> raw) {
        if (raw == null) return null;
        StringBuilder joined = new StringBuilder();
        Set<String> seen = new HashSet<>();
        int bytes = 0, examined = 0;
        for (Object entry : raw) {
            if (seen.size() == MAX_WORDS || ++examined > MAX_EXAMINED) break;
            if (!(entry instanceof String)) continue;
            String word = (String) entry;
            // Bound the work per entry before normalizing an arbitrarily long string.
            if (word.length() > 4 * CustomTerms.MAX_TERM_CODE_POINTS) continue;
            word = DictionaryText.normalize(word);
            if (!usable(word)) continue;
            String key = word.toLowerCase(Locale.ROOT);
            if (seen.contains(key)) continue;
            int size = word.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > MAX_TOTAL_BYTES) break;
            seen.add(key);
            bytes += size;
            if (joined.length() > 0) joined.append('\n');
            joined.append(word);
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    private static boolean usable(String word) {
        if (word.isEmpty() || word.contains("<|") || word.contains("|>")) return false;
        if (word.codePointCount(0, word.length()) > CustomTerms.MAX_TERM_CODE_POINTS) return false;
        for (int offset = 0; offset < word.length();) {
            int codePoint = word.codePointAt(offset);
            if (Character.isISOControl(codePoint) || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }
}
