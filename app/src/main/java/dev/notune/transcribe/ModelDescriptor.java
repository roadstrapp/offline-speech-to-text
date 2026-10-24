package dev.notune.transcribe;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** One immutable, capability-honest catalog entry shared by policy and UI. */
public final class ModelDescriptor {
    public enum Capability { SUPPORTED, UNSUPPORTED, UNKNOWN }
    public enum Provenance { EXACT_PINNED, SOURCE_LINK_ONLY, USER_IMPORTED }
    public enum SizeTier { COMPACT, PHONE_FRIENDLY, LARGE, VERY_LARGE }

    public final String id;
    public final String displayName;
    public final String architecture;
    public final String backend;
    public final String fileName;
    public final long byteSize;
    public final String sha256;
    public final String downloadUrl;
    public final String sourceUrl;
    public final String licenseId;
    public final String licenseUrl;
    public final Set<String> abis;
    public final boolean requiresDotProduct;
    public final boolean requiresFp16;
    public final Set<String> languages;
    public final Capability languageDetection;
    public final Capability translation;
    public final Capability streaming;
    public final Capability prompt;
    public final long minimumRamBytes;
    public final long minimumFreeBytes;
    public final Provenance provenance;
    /**
     * Supporting model (such as the language detector) that is installed beside the speech
     * models, never listed with them and never made the active model.
     */
    public final boolean auxiliary;

    ModelDescriptor(String id, String displayName, String architecture, String backend,
                    String fileName, long byteSize, String sha256, String downloadUrl,
                    String sourceUrl, String licenseId, String licenseUrl,
                    Set<String> abis, boolean requiresDotProduct, boolean requiresFp16,
                    Set<String> languages, Capability languageDetection,
                    Capability translation, Capability streaming, Capability prompt,
                    long minimumRamBytes, long minimumFreeBytes, Provenance provenance) {
        this(id, displayName, architecture, backend, fileName, byteSize, sha256, downloadUrl,
                sourceUrl, licenseId, licenseUrl, abis, requiresDotProduct, requiresFp16,
                languages, languageDetection, translation, streaming, prompt,
                minimumRamBytes, minimumFreeBytes, provenance, false);
    }

    ModelDescriptor(String id, String displayName, String architecture, String backend,
                    String fileName, long byteSize, String sha256, String downloadUrl,
                    String sourceUrl, String licenseId, String licenseUrl,
                    Set<String> abis, boolean requiresDotProduct, boolean requiresFp16,
                    Set<String> languages, Capability languageDetection,
                    Capability translation, Capability streaming, Capability prompt,
                    long minimumRamBytes, long minimumFreeBytes, Provenance provenance,
                    boolean auxiliary) {
        this.auxiliary = auxiliary;
        this.id = id;
        this.displayName = displayName;
        this.architecture = architecture;
        this.backend = backend;
        this.fileName = fileName;
        this.byteSize = byteSize;
        this.sha256 = sha256;
        this.downloadUrl = downloadUrl;
        this.sourceUrl = sourceUrl;
        this.licenseId = licenseId;
        this.licenseUrl = licenseUrl;
        this.abis = Collections.unmodifiableSet(new LinkedHashSet<>(abis));
        this.requiresDotProduct = requiresDotProduct;
        this.requiresFp16 = requiresFp16;
        this.languages = Collections.unmodifiableSet(new LinkedHashSet<>(languages));
        this.languageDetection = languageDetection;
        this.translation = translation;
        this.streaming = streaming;
        this.prompt = prompt;
        this.minimumRamBytes = minimumRamBytes;
        this.minimumFreeBytes = minimumFreeBytes;
        this.provenance = provenance;
    }

    public boolean supportsLanguage(String languageTag) {
        if (languageTag == null || languageTag.trim().isEmpty()) {
            return languageDetection == Capability.SUPPORTED;
        }
        String language = Locale.forLanguageTag(languageTag).getLanguage();
        return !language.isEmpty() && languages.contains(language);
    }

    public boolean isVerifiedDownload() {
        return provenance == Provenance.EXACT_PINNED
                && byteSize > 0
                && sha256 != null && sha256.matches("[0-9a-f]{64}")
                && downloadUrl != null && downloadUrl.startsWith("https://");
    }

    /** Coarse, deliberately conservative device guidance rather than a speed promise. */
    public SizeTier sizeTier() {
        if (byteSize < 128L * 1024L * 1024L) return SizeTier.COMPACT;
        if (byteSize < 768L * 1024L * 1024L) return SizeTier.PHONE_FRIENDLY;
        if (byteSize < 2L * 1024L * 1024L * 1024L) return SizeTier.LARGE;
        return SizeTier.VERY_LARGE;
    }
}
