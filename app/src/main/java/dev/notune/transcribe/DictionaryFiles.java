package dev.notune.transcribe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Bounded UTF-8 reads and complete atomic publications for private dictionary files. */
final class DictionaryFiles {
    private DictionaryFiles() {}

    static String read(File file, int maximumBytes) throws IOException {
        byte[] bytes = new byte[maximumBytes + 1];
        int count = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            int next;
            while (count < bytes.length
                    && (next = input.read(bytes, count, bytes.length - count)) != -1) {
                count += next;
            }
        }
        if (count > maximumBytes) throw new IOException("dictionary exceeds limit");
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, count)).toString();
    }

    static void write(File filesDir, String name, String text) throws IOException {
        File target = new File(filesDir, name);
        if (text.isEmpty()) {
            Files.deleteIfExists(target.toPath());
            return;
        }
        File staging = File.createTempFile(".dictionary-", ".tmp", filesDir);
        try {
            try (FileOutputStream output = new FileOutputStream(staging)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            // Fail without replacing the old file when atomic publication is unavailable.
            Files.move(staging.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staging.toPath());
        }
    }
}
