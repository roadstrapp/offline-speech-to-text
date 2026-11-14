package dev.notune.transcribe;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/** Spoken-language storage and the auxiliary language detector's identity. */
public final class SpokenLanguagesTest {
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("spoken-languages").toFile();
        assert SpokenLanguages.read(dir).isEmpty();

        SpokenLanguages.write(dir, Arrays.asList("en", "de"));
        assert Arrays.asList("en", "de").equals(SpokenLanguages.read(dir));
        assert !new File(dir, "model_language").exists();

        // A single language is a plain hint, not a detection list.
        SpokenLanguages.write(dir, Collections.singletonList("de"));
        assert SpokenLanguages.read(dir).isEmpty();
        assert "de".equals(new String(Files.readAllBytes(new File(dir, "model_language").toPath())));

        // Clearing leaves the existing hint alone.
        SpokenLanguages.write(dir, Collections.emptyList());
        assert new File(dir, "model_language").exists();

        Files.write(new File(dir, "model_languages").toPath(), " EN-us, de_DE ,en,x1 ".getBytes());
        assert Arrays.asList("en", "de").equals(SpokenLanguages.read(dir));

        // Alphabetical display order must not replace an explicit Slovak primary choice.
        SpokenLanguages.write(dir, Collections.singletonList("sk"));
        SpokenLanguages.write(dir, Arrays.asList("en", "sk"));
        assert Arrays.asList("sk", "en").equals(SpokenLanguages.read(dir));
        SpokenLanguages.write(dir, Arrays.asList("en", "de", "sk"));
        assert Arrays.asList("sk", "en", "de").equals(SpokenLanguages.read(dir));
        SpokenLanguages.write(dir, Arrays.asList("en", "de"));
        assert !SpokenLanguages.read(dir).contains("sk");

        assert !SpokenLanguages.needsDetector(Collections.singletonList("en"));
        assert SpokenLanguages.needsDetector(Arrays.asList("en", "de"));

        // Which models act on a language choice (checked against each GGUF's own metadata).
        for (String parakeet : new String[] {"parakeet-tdt-0.6b-v3-Q4_K_M.gguf",
                "parakeet-tdt-0.6b-v3-Q8_0.gguf", "parakeet-primeline-Q4_K_M.gguf"}) {
            assert ModelCatalog.findByFileName(parakeet).languageChoice
                    == ModelDescriptor.Capability.UNSUPPORTED : parakeet;
        }
        for (String follows : new String[] {"whisper-small-Q4_K_M.gguf",
                "canary-1b-v2-Q4_K_M.gguf", "nemotron-3.5-asr-streaming-0.6b-Q4_K_M.gguf"}) {
            assert ModelCatalog.findByFileName(follows).languageChoice
                    == ModelDescriptor.Capability.SUPPORTED : follows;
        }
        assert ModelCatalog.findByFileName("whisper-small.en-Q4_K_M.gguf").languageChoice
                == ModelDescriptor.Capability.UNSUPPORTED;

        ModelDescriptor detector = ModelCatalog.languageId();
        assert detector.auxiliary && detector.isVerifiedDownload();
        assert !ModelCatalog.verifiedDefault().auxiliary;
        assert ModelCatalog.findByFileName(detector.fileName) == null;
        assert !SpokenLanguages.detectorInstalled(dir);

        // Installing it lands in aux-models, leaves the active model alone and verifies size.
        File staged = new File(dir, "staged");
        Files.write(staged.toPath(), new byte[(int) detector.byteSize]);
        File aux = new File(new File(dir, ModelInstaller.AUXILIARY_DIRECTORY), detector.fileName);
        aux.getParentFile().mkdirs();
        Files.move(staged.toPath(), aux.toPath());
        assert !SpokenLanguages.detectorInstalled(dir) : "size-only file must not be trusted";
        assert !new File(dir, "active_model").exists();
        byte[] payload = "synthetic auxiliary model".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder hash = new StringBuilder();
        for (byte value : java.security.MessageDigest.getInstance("SHA-256").digest(payload))
            hash.append(String.format("%02x", value & 255));
        ModelDescriptor fixture = new ModelDescriptor("aux-test", "Auxiliary test", "Language ID",
                detector.backend, "aux-test.gguf", payload.length, hash.toString(),
                "https://example.invalid/aux-test.gguf", "https://example.invalid", "test", "https://example.invalid",
                detector.abis, false, false, detector.languages, detector.languageDetection,
                detector.translation, detector.streaming, detector.prompt, 0, 0,
                ModelDescriptor.Provenance.EXACT_PINNED, true);
        File pointer = new File(dir, "active_model");
        Files.write(pointer.toPath(), "existing.gguf".getBytes());
        File valid = new File(dir, "valid.part"); Files.write(valid.toPath(), payload);
        assert ModelInstaller.installVerified(dir, valid, fixture).equals(fixture.fileName);
        File destination = new File(new File(dir, ModelInstaller.AUXILIARY_DIRECTORY), fixture.fileName);
        assert VerifiedModelProvenance.isVerified(dir, destination, fixture);
        assert "existing.gguf".equals(new String(Files.readAllBytes(pointer.toPath())));
        File duplicate = new File(dir, "duplicate.part"); Files.write(duplicate.toPath(), payload);
        ModelInstaller.installVerified(dir, duplicate, fixture);
        assert !duplicate.exists();
        Files.write(destination.toPath(), new byte[payload.length]);
        File replacement = new File(dir, "replacement.part"); Files.write(replacement.toPath(), payload);
        ModelInstaller.installVerified(dir, replacement, fixture);
        assert java.util.Arrays.equals(payload, Files.readAllBytes(destination.toPath()));
        File bad = new File(dir, "bad.part"); Files.write(bad.toPath(), new byte[payload.length]);
        boolean rejected = false;
        try { ModelInstaller.installVerified(dir, bad, fixture); }
        catch (java.io.IOException expected) { rejected = true; }
        assert rejected : "same-size corrupt auxiliary model must be rejected";
        assert "existing.gguf".equals(new String(Files.readAllBytes(pointer.toPath())));

    }
}
