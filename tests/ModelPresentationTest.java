package dev.notune.transcribe;

/** Checks that the compact UI label never changes the exact artifact identity. */
public final class ModelPresentationTest {
    public static void main(String[] args) {
        ModelDescriptor standard = ModelCatalog.verifiedDefault();
        ModelDescriptor prime = ModelCatalog.findById("parakeet-primeline-q4km");
        assert prime != null;
        assert "Parakeet v3".equals(ModelPresentation.nameForFile(standard.fileName));
        assert "Parakeet primeLine".equals(ModelPresentation.nameForFile(prime.fileName));
        assert !ModelPresentation.shortName(standard).equals(ModelPresentation.shortName(prime));
        assert "Whisper small".equals(ModelPresentation.nameForFile(
                "whisper-small-Q4_K_M.gguf"));
        String imported = ModelPresentation.nameForFile("my_local_recording_model.gguf");
        assert "my local recording model".equals(imported);
        assert !imported.contains(".gguf");
        assert ModelPresentation.importedName("a".repeat(70) + ".gguf").length() <= 49;
    }
}
