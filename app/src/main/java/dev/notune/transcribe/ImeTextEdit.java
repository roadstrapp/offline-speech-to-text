package dev.notune.transcribe;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.Objects;

/** Pure, Android-independent IME editing policy used by focused JVM tests. */
final class ImeTextEdit {
    private static final int TYPE_MASK_CLASS = 0x0000000f;
    private static final int TYPE_CLASS_TEXT = 0x00000001;
    private static final int TYPE_CLASS_NUMBER = 0x00000002;
    private static final int TYPE_MASK_VARIATION = 0x00000ff0;
    private static final int TYPE_TEXT_VARIATION_PASSWORD = 0x00000080;
    private static final int TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090;
    private static final int TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0;
    private static final int TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010;
    private static final int ZERO_WIDTH_JOINER = 0x200d;

    private ImeTextEdit() { }

    static boolean isTextEditor(int inputType, int initialSelection, int fieldId) {
        return !(inputType == 0 && initialSelection < 0 && fieldId == 0);
    }

    static String separateDictation(CharSequence before, String text) {
        if (text == null || text.isEmpty() || Character.isWhitespace(text.charAt(0))) return text;
        return before != null && before.length() > 0
                && !Character.isWhitespace(before.charAt(before.length() - 1)) ? " " + text : text;
    }

    static Range insertedSelection(int extractedOffset, int relativeCursor, int length) {
        if (extractedOffset < 0 || relativeCursor < 0 || length < 0) return null;
        long end = (long) extractedOffset + relativeCursor;
        long start = end - length;
        return start < 0 || end > Integer.MAX_VALUE ? null : new Range((int) start, (int) end);
    }

    static boolean isSensitiveEditor(int inputType, int imeOptions) {
        // IME_FLAG_NO_PERSONALIZED_LEARNING is a request not to retain or learn
        // editor content. This keyboard never does either, so privacy-conscious
        // apps may still use local dictation. Only credential fields are blocked.
        int inputClass = inputType & TYPE_MASK_CLASS;
        int variation = inputType & TYPE_MASK_VARIATION;
        if (inputClass == TYPE_CLASS_TEXT) {
            return variation == TYPE_TEXT_VARIATION_PASSWORD
                    || variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == TYPE_TEXT_VARIATION_WEB_PASSWORD;
        }
        return inputClass == TYPE_CLASS_NUMBER && variation == TYPE_NUMBER_VARIATION_PASSWORD;
    }

    /** Returns a clamped UTF-16 selection spanning {@code clusterDelta} whole clusters. */
    static Range selectionForClusterDelta(String text, int cursor, int clusterDelta) {
        String safeText = text == null ? "" : text;
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(safeText);
        int anchor = clusterBoundaryAtOrBefore(safeText, cursor, iterator);
        int edge = anchor;
        int count = Math.abs(clusterDelta);
        for (int i = 0; i < count; i++) {
            int next = clusterDelta < 0
                    ? previousClusterBoundary(safeText, edge, iterator)
                    : nextClusterBoundary(safeText, edge, iterator);
            if (next == edge) break;
            edge = next;
        }
        return new Range(Math.min(anchor, edge), Math.max(anchor, edge));
    }

    /** Extends an existing selection from the edge indicated by {@code clusterDelta}. */
    static Range selectionForClusterDelta(
            String text, int selectionStart, int selectionEnd, int clusterDelta) {
        String safeText = text == null ? "" : text;
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(safeText);
        int lower = clusterBoundaryAtOrBefore(
                safeText, Math.min(selectionStart, selectionEnd), iterator);
        int upper = clusterBoundaryAtOrAfter(
                safeText, Math.max(selectionStart, selectionEnd), iterator);
        if (clusterDelta == 0) return new Range(lower, upper);

        int edge = clusterDelta < 0 ? lower : upper;
        for (int i = 0; i < Math.abs(clusterDelta); i++) {
            int next = clusterDelta < 0
                    ? previousClusterBoundary(safeText, edge, iterator)
                    : nextClusterBoundary(safeText, edge, iterator);
            if (next == edge) break;
            edge = next;
        }
        return clusterDelta < 0 ? new Range(edge, upper) : new Range(lower, edge);
    }

    private static int clampToCodePointBoundary(String text, int index) {
        int clamped = Math.max(0, Math.min(index, text.length()));
        if (clamped > 0 && clamped < text.length()
                && Character.isLowSurrogate(text.charAt(clamped))
                && Character.isHighSurrogate(text.charAt(clamped - 1))) {
            clamped--;
        }
        return clamped;
    }

    private static int clusterBoundaryAtOrBefore(
            String text, int index, BreakIterator iterator) {
        int target = clampToCodePointBoundary(text, index);
        if (target == 0) return 0;
        if (iterator.isBoundary(target) && !shouldSuppressBoundary(text, target)) return target;
        int boundary = iterator.preceding(target);
        while (boundary > 0 && shouldSuppressBoundary(text, boundary)) {
            boundary = iterator.preceding(boundary);
        }
        return Math.max(0, boundary);
    }

    private static int clusterBoundaryAtOrAfter(
            String text, int index, BreakIterator iterator) {
        int target = clampToCodePointBoundary(text, index);
        if (target == text.length()) return target;
        if (iterator.isBoundary(target) && !shouldSuppressBoundary(text, target)) return target;
        return nextClusterBoundary(text, target, iterator);
    }

    private static int previousClusterBoundary(
            String text, int index, BreakIterator iterator) {
        if (index <= 0) return 0;
        int boundary = iterator.preceding(index);
        while (boundary > 0 && shouldSuppressBoundary(text, boundary)) {
            boundary = iterator.preceding(boundary);
        }
        return Math.max(0, boundary);
    }

    private static int nextClusterBoundary(
            String text, int index, BreakIterator iterator) {
        if (index >= text.length()) return text.length();
        int boundary = iterator.following(index);
        while (boundary > 0 && boundary < text.length()
                && shouldSuppressBoundary(text, boundary)) {
            boundary = iterator.following(boundary);
        }
        return boundary == BreakIterator.DONE ? text.length() : boundary;
    }

    /**
     * Older Android BreakIterator tables can expose boundaries inside newer Unicode clusters.
     * Merge those boundaries conservatively so deletion may select too much, never half a glyph.
     */
    private static boolean shouldSuppressBoundary(String text, int boundary) {
        if (boundary <= 0 || boundary >= text.length()) return false;
        int previous = text.codePointBefore(boundary);
        int next = text.codePointAt(boundary);
        if (previous == '\r' && next == '\n') return true;
        if (next == ZERO_WIDTH_JOINER || isExtender(next)) {
            return true;
        }
        if ((previous == ZERO_WIDTH_JOINER || isVirama(previous))
                && !isClusterTerminator(next)) return true;
        if (isRegionalIndicator(previous) && isRegionalIndicator(next)) {
            int count = 0;
            int cursor = boundary;
            while (cursor > 0 && isRegionalIndicator(text.codePointBefore(cursor))) {
                cursor = text.offsetByCodePoints(cursor, -1);
                count++;
            }
            return (count & 1) == 1;
        }
        int previousHangul = hangulClass(previous);
        int nextHangul = hangulClass(next);
        return previousHangul == 1 && (nextHangul == 1 || nextHangul == 2
                || nextHangul == 4 || nextHangul == 5)
                || (previousHangul == 2 || previousHangul == 4)
                && (nextHangul == 2 || nextHangul == 3)
                || (previousHangul == 3 || previousHangul == 5) && nextHangul == 3;
    }

    private static boolean isVirama(int codePoint) {
        String name = Character.getName(codePoint);
        return name != null && (name.contains("VIRAMA")
                || name.contains("HALANT") || name.contains("COENG"));
    }

    private static boolean isClusterTerminator(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isWhitespace(codePoint)
                || type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    // 1=L, 2=V, 3=T, 4=LV, 5=LVT, 0=other (Unicode grapheme GB6-GB8).
    private static int hangulClass(int codePoint) {
        if ((codePoint >= 0x1100 && codePoint <= 0x115f)
                || (codePoint >= 0xa960 && codePoint <= 0xa97c)) return 1;
        if ((codePoint >= 0x1160 && codePoint <= 0x11a7)
                || (codePoint >= 0xd7b0 && codePoint <= 0xd7c6)) return 2;
        if ((codePoint >= 0x11a8 && codePoint <= 0x11ff)
                || (codePoint >= 0xd7cb && codePoint <= 0xd7fb)) return 3;
        if (codePoint >= 0xac00 && codePoint <= 0xd7a3) {
            return (codePoint - 0xac00) % 28 == 0 ? 4 : 5;
        }
        return 0;
    }

    private static boolean isExtender(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || (codePoint >= 0xfe00 && codePoint <= 0xfe0f)
                || (codePoint >= 0xe0100 && codePoint <= 0xe01ef)
                || (codePoint >= 0xe0020 && codePoint <= 0xe007f)
                || (codePoint >= 0x1f3fb && codePoint <= 0x1f3ff);
    }

    private static boolean isRegionalIndicator(int codePoint) {
        return codePoint >= 0x1f1e6 && codePoint <= 0x1f1ff;
    }

    static final class Range {
        final int start;
        final int end;

        Range(int start, int end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Range)) return false;
            Range range = (Range) other;
            return start == range.start && end == range.end;
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, end);
        }

        @Override
        public String toString() {
            return "Range{" + start + ", " + end + "}";
        }
    }
}
