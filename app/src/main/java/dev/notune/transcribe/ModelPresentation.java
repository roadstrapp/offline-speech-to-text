package dev.notune.transcribe;

/** Human-readable labels. File names and descriptor IDs remain the storage identity. */
public final class ModelPresentation {
    private ModelPresentation() { }

    public static String nameForFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "Parakeet v3";
        ModelDescriptor descriptor = ModelCatalog.findByFileName(fileName);
        return descriptor == null ? importedName(fileName) : shortName(descriptor);
    }

    public static String shortName(ModelDescriptor descriptor) {
        if (descriptor == null) return "Model";
        if ("parakeet-tdt-0.6b-v3-q4km".equals(descriptor.id)) return "Parakeet v3";
        if ("parakeet-primeline-q4km".equals(descriptor.id)) return "Parakeet primeLine";
        String label = descriptor.displayName.replaceAll("\\s*·\\s*Q[0-9][A-Za-z0-9_]*$", "");
        return label.trim();
    }

    public static String importedName(String fileName) {
        String label = fileName.replaceAll("(?i)\\.gguf$", "")
                .replace('_', ' ').replace('-', ' ').trim();
        if (label.isEmpty() || label.matches("(?i)[0-9a-f]{24,}")) return "Imported model";
        if (label.codePointCount(0, label.length()) > 48)
            label = label.substring(0, label.offsetByCodePoints(0, 48)).trim() + "…";
        return label;
    }
}
