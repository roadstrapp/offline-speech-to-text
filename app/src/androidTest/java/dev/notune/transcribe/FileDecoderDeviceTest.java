package dev.notune.transcribe;

import android.app.Instrumentation;
import android.net.Uri;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;

/** Exercises the production codec/resampler with the packaged public fixture. */
final class FileDecoderDeviceTest {
    static void run(Instrumentation instrumentation) throws Exception {
        File fixture = new File(instrumentation.getTargetContext().getCacheDir(), "decoder-validation.wav");
        try {
            try (InputStream audio = instrumentation.getTargetContext().getAssets().open("bench.wav")) {
                Files.copy(audio, fixture.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            AtomicLong received = new AtomicLong();
            AudioFileDecoder decoder = new AudioFileDecoder(instrumentation.getTargetContext(), Uri.fromFile(fixture));
            long count = decoder.decode((samples, length) -> {
                if (length <= 0 || length > 160000 || length > samples.length)
                    throw new AssertionError("unbounded decoder block");
                for (int i = 0; i < length; ++i) if (!Float.isFinite(samples[i]))
                    throw new AssertionError("non-finite PCM output");
                received.addAndGet(length);
                return true;
            }, () -> false);
            if (count <= 3200 || received.get() != count) throw new AssertionError("decoded samples lost");
            AtomicLong cancelledCount = new AtomicLong();
            new AudioFileDecoder(instrumentation.getTargetContext(), Uri.fromFile(fixture))
                    .decode((samples, length) -> { cancelledCount.addAndGet(length); return false; }, () -> false);
            if (cancelledCount.get() <= 0 || cancelledCount.get() > 160000)
                throw new AssertionError("decoder continued after sink cancellation");
        } finally {
            Files.deleteIfExists(fixture.toPath());
        }
    }
}
