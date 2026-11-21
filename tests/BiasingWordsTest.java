package dev.notune.transcribe;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class BiasingWordsTest {
    public static void main(String[] args) {
        nothingUsableIsNoList();
        entriesAreCleanedOneByOneInTheCallersOrder();
        countAndTotalLengthAreBounded();
        hostileListsAreBoundedWork();
    }

    private static void nothingUsableIsNoList() {
        check(BiasingWords.sanitize(null) == null, "no extra");
        check(BiasingWords.sanitize(Collections.emptyList()) == null, "empty list");
        check(BiasingWords.sanitize(Arrays.asList("", "   ", "\n\t", null)) == null, "blank entries");
    }

    private static void entriesAreCleanedOneByOneInTheCallersOrder() {
        List<Object> raw = new ArrayList<>();
        raw.add("  Droneboard ");
        raw.add(null);
        raw.add(42);                         // not a string
        raw.add("Zapstore\tRelay");          // inner whitespace collapses
        raw.add("first\nsecond");            // a line break cannot smuggle a second entry
        raw.add("droneboard");               // duplicate, ignoring case
        raw.add("<|notimestamps|>");         // model control token
        raw.add("be\u0007ll");               // control character
        raw.add("lone\uD800");               // unpaired surrogate
        raw.add(repeat("x", 81));            // longer than a preferred word may be
        raw.add("Košice");
        equal("Droneboard\nZapstore Relay\nfirst second\nKošice", BiasingWords.sanitize(raw),
                "unusable entries are dropped alone and order is kept");
        equal("android.speech.extra.BIASING_STRINGS", BiasingWords.EXTRA, "the platform's extra name");
    }

    private static void countAndTotalLengthAreBounded() {
        List<String> many = new ArrayList<>();
        for (int index = 0; index < 5000; index++) many.add("word" + index);
        String[] kept = BiasingWords.sanitize(many).split("\n");
        equal(BiasingWords.MAX_WORDS, kept.length, "count bound");
        equal("word0", kept[0], "the first words are the ones kept");
        equal("word999", kept[999], "the first thousand, in order");

        List<String> wide = new ArrayList<>();
        for (int index = 0; index < 1000; index++) {
            wide.add(String.format("%04d", index) + repeat("é", 38)); // 80 UTF-8 bytes each
        }
        String joined = BiasingWords.sanitize(wide);
        int bytes = 0;
        for (String word : joined.split("\n")) bytes += word.getBytes(StandardCharsets.UTF_8).length;
        check(bytes <= BiasingWords.MAX_TOTAL_BYTES, "total length bound");
        equal(BiasingWords.MAX_TOTAL_BYTES / 80, joined.split("\n").length, "whole words up to the byte bound");
        check(joined.startsWith("0000"), "byte bound keeps the first words");
    }

    private static void hostileListsAreBoundedWork() {
        // A million unusable entries: only the first MAX_EXAMINED are looked at.
        List<String> junk = Collections.nCopies(1_000_000, repeat("y", 100_000).substring(0, 400));
        check(BiasingWords.sanitize(junk) == null, "unusable flood");
        List<Object> late = new ArrayList<>(Collections.nCopies(BiasingWords.MAX_EXAMINED, (Object) ""));
        late.add("TooLate");
        check(BiasingWords.sanitize(late) == null, "entries after the examined prefix are ignored");
    }

    private static String repeat(String text, int count) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < count; index++) builder.append(text);
        return builder.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
