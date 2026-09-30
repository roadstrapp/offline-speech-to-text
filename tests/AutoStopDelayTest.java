package dev.notune.transcribe;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

public final class AutoStopDelayTest {
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("auto-stop-test-").toFile();
        try {
            check(AutoStopDelay.get(directory) == 2000, "missing preference changes default");
            for (String invalid : new String[]{"", "0", "-1", "1500", "8000", "3.0",
                    "NaN", "3000\n", " 3000", "3000\u0000", "٣٠٠٠", "3000xxxxxxxxxxxxxxxx"}) {
                check(AutoStopDelay.resolve(invalid) == 2000, "malformed choice accepted");
                Files.write(new File(directory, AutoStopDelay.FILE_NAME).toPath(),
                        invalid.getBytes(StandardCharsets.UTF_8));
                check(AutoStopDelay.get(directory) == 2000, "malformed file accepted");
            }
            for (int delay : new int[]{2000, 3000, 5000}) {
                AutoStopDelay.set(directory, delay);
                check(AutoStopDelay.get(directory) == delay, "saved choice lost");
                check(AutoStopDelay.atPosition(AutoStopDelay.positionOf(delay)) == delay,
                        "displayed choice differs from capture choice");
            }
            // Rejected values must leave the existing complete preference intact.
            try {
                AutoStopDelay.set(directory, 0);
                throw new AssertionError("invalid write succeeded");
            } catch (IllegalArgumentException expected) { }
            check(AutoStopDelay.get(directory) == 5000, "failed write lost prior choice");
            // A reader racing atomic replacements must never fall back to two
            // seconds because it observed a partial write of a longer choice.
            AutoStopDelay.set(directory, 3000);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try {
                    for (int i = 0; i < 80; i++) AutoStopDelay.set(directory, i % 2 == 0 ? 5000 : 3000);
                } catch (Throwable error) { failure.set(error); }
            });
            writer.start();
            do {
                int delay = AutoStopDelay.get(directory);
                check(delay == 3000 || delay == 5000, "reader saw partial replacement");
            } while (writer.isAlive());
            writer.join();
            if (failure.get() != null) throw new AssertionError(failure.get());
            check(directory.list().length == 1, "temporary preference files leaked");
            // An unavailable destination reports failure instead of pretending
            // that the choice was saved.
            try {
                AutoStopDelay.set(new File(directory, "missing-directory"), 3000);
                throw new AssertionError("unavailable destination write succeeded");
            } catch (IOException expected) { }
        } finally {
            for (File file : directory.listFiles()) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
        System.out.println("Auto-stop persistence, invalid-input and atomic-reader tests passed.");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
