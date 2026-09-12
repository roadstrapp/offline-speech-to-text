package dev.notune.transcribe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Persists the active imported GGUF selection after a successful import. */
final class ImportedModelSelection {
    private static final String ACTIVE_MODEL_FILE = "active_model";

    private ImportedModelSelection() {}

    static void activate(File filesDir, String modelName) throws IOException {
        if (modelName == null || modelName.isEmpty()) {
            throw new IllegalArgumentException("modelName must not be empty");
        }
        File activeModel = new File(filesDir, ACTIVE_MODEL_FILE);
        try (FileOutputStream out = new FileOutputStream(activeModel)) {
            out.write(modelName.getBytes(StandardCharsets.UTF_8));
        }
    }
}
