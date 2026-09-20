package dev.notune.transcribe;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Shared, side-effect-free model availability checks for setup UI. */
final class SpeechModelInventory {
    private SpeechModelInventory() { }

    static boolean hasUsableModel(File filesDir, boolean packagedModelAvailable) {
        if (filesDir == null) return false;
        File selected = new File(filesDir, "active_model");
        if (selected.isFile()) {
            try {
                String name = new String(Files.readAllBytes(selected.toPath()),
                        StandardCharsets.UTF_8).trim();
                if (isSafeModelName(name)
                        && new File(new File(filesDir, "models"), name).isFile()) {
                    return true;
                }
            } catch (IOException ignored) { }
        }
        return packagedModelAvailable || hasRetainedBuiltInModel(filesDir);
    }

    static boolean hasRetainedBuiltInModel(File filesDir) {
        File directory = new File(filesDir, "builtin-model");
        File retained = new File(directory, DefaultModelContract.FILE_NAME);
        return new File(directory, ".extraction_complete").isFile()
                && retained.isFile() && retained.length() == DefaultModelContract.BYTE_SIZE;
    }

    private static boolean isSafeModelName(String name) {
        return !name.isEmpty() && name.equals(new File(name).getName())
                && name.toLowerCase(java.util.Locale.ROOT).endsWith(".gguf");
    }
}
