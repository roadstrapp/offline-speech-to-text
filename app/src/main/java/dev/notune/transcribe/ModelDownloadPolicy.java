package dev.notune.transcribe;

/** Pure validation rules used by the HTTP downloader. */
public final class ModelDownloadPolicy {
    public enum ResumeAction { APPEND, RESTART }

    private ModelDownloadPolicy() {}

    public static ResumeAction resumeAction(long existingBytes, int responseCode,
                                            String contentRange, String savedValidator,
                                            String responseValidator) {
        if (existingBytes <= 0 || responseCode != 206) return ResumeAction.RESTART;
        String prefix = "bytes " + existingBytes + "-";
        if (contentRange == null || !contentRange.startsWith(prefix)) return ResumeAction.RESTART;
        if (savedValidator == null || savedValidator.isEmpty()
                || responseValidator == null || !savedValidator.equals(responseValidator)) {
            return ResumeAction.RESTART;
        }
        return ResumeAction.APPEND;
    }

    public static boolean acceptArtifact(long bytes, String sha256, ModelDescriptor descriptor) {
        return descriptor != null && descriptor.isVerifiedDownload()
                && descriptor.byteSize == bytes
                && descriptor.sha256.equalsIgnoreCase(sha256 == null ? "" : sha256);
    }
}
