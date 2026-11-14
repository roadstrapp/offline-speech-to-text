package dev.notune.transcribe;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The languages the user speaks. Two or more switch recognition to per-utterance detection among
 * exactly those languages (see {@code engine.rs}); one is an ordinary fixed language hint.
 */
final class SpokenLanguages {
    static final String LIST_FILE = "model_languages";
    private static final String HINT_FILE = "model_language";
    static final int MAX = 8;

    private SpokenLanguages() {}

    /** Primary language codes (`en`, `de`) from the multi-language list; empty when unset. */
    static List<String> read(File filesDir) {
        List<String> languages = new ArrayList<>();
        for (String entry : readFile(new File(filesDir, LIST_FILE)).split("[,\\s]+")) {
            String code = primary(entry);
            if (!code.isEmpty() && !languages.contains(code) && languages.size() < MAX) {
                languages.add(code);
            }
        }
        return languages;
    }

    /**
     * Stores the selection. Zero keeps the existing language hint untouched; one becomes the
     * plain hint; two or more go to the list and return the hint to automatic, since detection
     * supplies it per utterance. Preserve the fixed hint, previous first language or device
     * language as the first candidate for audio too short to identify.
     */
    static void write(File filesDir, List<String> codes) throws IOException {
        File list = new File(filesDir, LIST_FILE);
        File hint = new File(filesDir, HINT_FILE);
        if (codes.size() >= 2) {
            List<String> ordered = new ArrayList<>(codes);
            String preferred = primary(readFile(hint));
            if (!ordered.contains(preferred)) {
                List<String> previous = read(filesDir);
                preferred = previous.isEmpty() ? "" : previous.get(0);
            }
            if (!ordered.contains(preferred)) preferred = primary(Locale.getDefault().getLanguage());
            if (ordered.remove(preferred)) ordered.add(0, preferred);
            Files.write(list.toPath(), String.join(",", ordered).getBytes(StandardCharsets.UTF_8));
            Files.deleteIfExists(hint.toPath());
        } else {
            Files.deleteIfExists(list.toPath());
            if (codes.size() == 1) {
                Files.write(hint.toPath(), codes.get(0).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    static boolean needsDetector(List<String> codes) {
        return codes.size() >= 2;
    }

    static boolean detectorInstalled(File filesDir) {
        ModelDescriptor detector = ModelCatalog.languageId();
        File file = new File(new File(filesDir, ModelInstaller.AUXILIARY_DIRECTORY),
                detector.fileName);
        return VerifiedModelProvenance.isVerified(filesDir, file, detector);
    }

    private static String primary(String tag) {
        String code = tag.trim().split("[-_]")[0].toLowerCase(Locale.ROOT);
        return code.matches("[a-z]{2,3}") ? code : "";
    }

    private static String readFile(File file) {
        try {
            return file.isFile()
                    ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
