package dev.notune.transcribe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** One bounded, atomic preference shared by the app and voice-keyboard process. */
final class AutoStopDelay {
    static final int DEFAULT_MS = 2000;
    static final String FILE_NAME = "auto_stop_silence_ms";
    private static final int MAX_BYTES = 16;

    private AutoStopDelay() {}

    static int resolve(String value) {
        if (value != null && value.length() <= MAX_BYTES) {
            switch (value) {
                case "3000": return 3000;
                case "5000": return 5000;
                case "2000": return DEFAULT_MS;
                default: break;
            }
        }
        return DEFAULT_MS;
    }

    static int get(File filesDir) {
        try (FileInputStream input = new FileInputStream(new File(filesDir, FILE_NAME))) {
            byte[] bytes = new byte[MAX_BYTES + 1];
            int count = 0;
            int next;
            while (count < bytes.length && (next = input.read()) != -1) {
                bytes[count++] = (byte) next;
            }
            if (count > MAX_BYTES) return DEFAULT_MS;
            return resolve(new String(bytes, 0, count, StandardCharsets.US_ASCII));
        } catch (IOException ignored) {
            return DEFAULT_MS;
        }
    }

    static void set(File filesDir, int milliseconds) throws IOException {
        if (milliseconds != 2000 && milliseconds != 3000 && milliseconds != 5000) {
            throw new IllegalArgumentException("unsupported stop delay");
        }
        // Unique staging files prevent concurrent saves from sharing a partially
        // written value. Readers see the old complete choice or the new one.
        File staging = File.createTempFile(".auto-stop-", ".tmp", filesDir);
        try {
            try (FileOutputStream output = new FileOutputStream(staging)) {
                output.write(Integer.toString(milliseconds).getBytes(StandardCharsets.US_ASCII));
                output.getFD().sync();
            }
            Files.move(staging.toPath(), new File(filesDir, FILE_NAME).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staging.toPath());
        }
    }

    static int atPosition(int position) {
        return position == 1 ? 3000 : position == 2 ? 5000 : DEFAULT_MS;
    }

    static int positionOf(int milliseconds) {
        return milliseconds == 3000 ? 1 : milliseconds == 5000 ? 2 : 0;
    }
}
