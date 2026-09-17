package dev.notune.transcribe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure deterministic hard-filter for verified catalog entries. */
public final class ModelRecommendation {
    public enum Status { RECOMMENDED, NO_SAFE_MATCH }
    public enum Goal { BALANCED, COMPACT, MULTILINGUAL, TRANSLATE, QUALITY }
    public enum Reason {
        ABI_UNSUPPORTED,
        CPU_FEATURE_MISSING,
        INSUFFICIENT_RAM,
        INSUFFICIENT_STORAGE,
        LANGUAGE_UNSUPPORTED,
        FEATURE_UNSUPPORTED,
        NO_SAFE_MATCH
    }

    public static final class DeviceProfile {
        public final boolean arm64;
        public final boolean dotProduct;
        public final boolean fp16;
        public final long totalRamBytes;
        public final long freeStorageBytes;
        public final Double benchmarkRealtimeFactor;

        public DeviceProfile(boolean arm64, boolean dotProduct, boolean fp16,
                             long totalRamBytes, long freeStorageBytes,
                             Double benchmarkRealtimeFactor) {
            this.arm64 = arm64;
            this.dotProduct = dotProduct;
            this.fp16 = fp16;
            this.totalRamBytes = totalRamBytes;
            this.freeStorageBytes = freeStorageBytes;
            this.benchmarkRealtimeFactor = benchmarkRealtimeFactor;
        }
    }

    public static final class Request {
        public final String languageTag;
        public final boolean translation;
        public final Goal goal;

        public Request(String languageTag, boolean translation) {
            this(languageTag, translation, translation ? Goal.TRANSLATE : Goal.BALANCED);
        }

        public Request(String languageTag, boolean translation, Goal goal) {
            this.languageTag = languageTag == null ? "" : languageTag;
            this.translation = translation;
            this.goal = goal == null ? Goal.BALANCED : goal;
        }
    }

    public static final class Result {
        public final Status status;
        public final ModelDescriptor model;
        public final List<Reason> reasons;

        Result(Status status, ModelDescriptor model, List<Reason> reasons) {
            this.status = status;
            this.model = model;
            this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
        }
    }

    private ModelRecommendation() {}

    public static Result recommend(DeviceProfile device, Request request) {
        ModelDescriptor model = candidateFor(request, device);
        if (model == null) {
            return new Result(Status.NO_SAFE_MATCH, null,
                    Collections.singletonList(Reason.NO_SAFE_MATCH));
        }
        List<Reason> reasons = new ArrayList<>();
        if (model.abis.contains("arm64-v8a") && !device.arm64) {
            reasons.add(Reason.ABI_UNSUPPORTED);
        }
        if ((model.requiresDotProduct && !device.dotProduct)
                || (model.requiresFp16 && !device.fp16)) {
            reasons.add(Reason.CPU_FEATURE_MISSING);
        }
        if (device.totalRamBytes < model.minimumRamBytes) reasons.add(Reason.INSUFFICIENT_RAM);
        if (device.freeStorageBytes < model.minimumFreeBytes) reasons.add(Reason.INSUFFICIENT_STORAGE);
        if (!model.supportsLanguage(request.languageTag)) reasons.add(Reason.LANGUAGE_UNSUPPORTED);
        if (request.translation && model.translation != ModelDescriptor.Capability.SUPPORTED) {
            reasons.add(Reason.FEATURE_UNSUPPORTED);
        }
        if (reasons.isEmpty()) {
            return new Result(Status.RECOMMENDED, model, reasons);
        }
        reasons.add(Reason.NO_SAFE_MATCH);
        return new Result(Status.NO_SAFE_MATCH, null, reasons);
    }

    private static ModelDescriptor candidateFor(Request request, DeviceProfile device) {
        String language = java.util.Locale.forLanguageTag(request.languageTag).getLanguage();
        boolean english = "en".equals(language);
        String preferredFile;
        switch (request.goal) {
            case COMPACT:
                preferredFile = english
                        ? "whisper-tiny.en-Q4_K_M.gguf" : "whisper-tiny-Q4_K_M.gguf";
                break;
            case MULTILINGUAL:
            case TRANSLATE:
                preferredFile = "whisper-small-Q4_K_M.gguf";
                break;
            case QUALITY:
                preferredFile = "whisper-large-v3-Q4_K_M.gguf";
                break;
            case BALANCED:
            default:
                ModelDescriptor defaultModel = ModelCatalog.verifiedDefault();
                if (!request.translation && (request.languageTag.isEmpty()
                        || defaultModel.supportsLanguage(request.languageTag))) {
                    return defaultModel;
                }
                preferredFile = "whisper-small-Q4_K_M.gguf";
                break;
        }
        ModelDescriptor preferred = ModelCatalog.findByFileName(preferredFile);
        if (preferred != null && hardCompatible(preferred, device)
                && languageCompatible(preferred, request)) {
            return preferred;
        }
        String[] fallbacks;
        switch (request.goal) {
            case QUALITY:
                fallbacks = new String[] {
                        "whisper-medium-Q4_K_M.gguf", "whisper-small-Q4_K_M.gguf",
                        "whisper-base-Q4_K_M.gguf", "whisper-tiny-Q4_K_M.gguf"};
                break;
            case COMPACT:
                fallbacks = new String[] {
                        "whisper-tiny-Q4_K_M.gguf", "whisper-base-Q4_K_M.gguf",
                        "whisper-small-Q4_K_M.gguf", "whisper-medium-Q4_K_M.gguf",
                        "whisper-large-v3-turbo-Q4_K_M.gguf", "whisper-large-v3-Q4_K_M.gguf"};
                break;
            case MULTILINGUAL:
            case TRANSLATE:
            case BALANCED:
            default:
                fallbacks = new String[] {
                        "whisper-small-Q4_K_M.gguf", "whisper-medium-Q4_K_M.gguf",
                        "whisper-large-v3-turbo-Q4_K_M.gguf", "whisper-large-v3-Q4_K_M.gguf",
                        "whisper-base-Q4_K_M.gguf", "whisper-tiny-Q4_K_M.gguf"};
                break;
        }
        for (String fallback : fallbacks) {
            ModelDescriptor candidate = ModelCatalog.findByFileName(fallback);
            if (candidate != null && hardCompatible(candidate, device)
                    && languageCompatible(candidate, request)) return candidate;
        }
        // Return the goal's preferred model so recommend() can expose the exact
        // incompatibility reasons instead of collapsing every miss into a generic error.
        return preferred;
    }

    private static boolean hardCompatible(ModelDescriptor model, DeviceProfile device) {
        return (!model.abis.contains("arm64-v8a") || device.arm64)
                && (!model.requiresDotProduct || device.dotProduct)
                && (!model.requiresFp16 || device.fp16)
                && device.totalRamBytes >= model.minimumRamBytes
                && device.freeStorageBytes >= model.minimumFreeBytes;
    }

    private static boolean languageCompatible(ModelDescriptor model, Request request) {
        if (!request.languageTag.isEmpty() && !model.supportsLanguage(request.languageTag)) {
            return false;
        }
        return !request.translation
                || model.translation == ModelDescriptor.Capability.SUPPORTED;
    }
}
