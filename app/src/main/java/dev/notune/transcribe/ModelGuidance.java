package dev.notune.transcribe;

import java.util.List;

/** Pure labels for coarse model fit. These are guidance, never benchmark promises. */
final class ModelGuidance {
    enum DeviceFit { GOOD, DEMANDING, UNSUPPORTED, NOT_ENOUGH_RAM, NOT_ENOUGH_STORAGE }

    private ModelGuidance() {}

    static DeviceFit fit(ModelDescriptor model, ModelRecommendation.DeviceProfile device) {
        if (!device.arm64 || (model.requiresDotProduct && !device.dotProduct)
                || (model.requiresFp16 && !device.fp16)) return DeviceFit.UNSUPPORTED;
        if (device.totalRamBytes < model.minimumRamBytes) return DeviceFit.NOT_ENOUGH_RAM;
        if (device.freeStorageBytes < model.minimumFreeBytes) return DeviceFit.NOT_ENOUGH_STORAGE;
        if (model.sizeTier() == ModelDescriptor.SizeTier.VERY_LARGE
                || (model.sizeTier() == ModelDescriptor.SizeTier.LARGE
                        && device.totalRamBytes < (8L << 30))
                || model.minimumRamBytes > device.totalRamBytes / 100L * 55L) {
            return DeviceFit.DEMANDING;
        }
        return DeviceFit.GOOD;
    }

    /** Largest option in one family that remains a conservative good fit. */
    static ModelDescriptor largestComfortableInFamily(
            List<ModelDescriptor> models, String family,
            ModelRecommendation.DeviceProfile device) {
        ModelDescriptor best = null;
        for (ModelDescriptor model : models) {
            if (!model.architecture.equals(family) || fit(model, device) != DeviceFit.GOOD) {
                continue;
            }
            if (best == null || model.byteSize > best.byteSize) best = model;
        }
        return best;
    }
}
