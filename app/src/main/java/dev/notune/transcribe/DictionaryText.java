package dev.notune.transcribe;

import java.text.Normalizer;

/** Shared canonical text form for private dictionary entries. */
final class DictionaryText {
    private DictionaryText() {}

    static String normalize(String value) {
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        StringBuilder result = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
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
