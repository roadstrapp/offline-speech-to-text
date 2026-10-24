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

        assert !SpokenLanguages.needsDetector(Collections.singletonList("en"));
        assert SpokenLanguages.needsDetector(Arrays.asList("en", "de"));

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
        assert SpokenLanguages.detectorInstalled(dir);
        assert !new File(dir, "active_model").exists();
    }
}
