package dev.notune.transcribe;

/** Pure labels for coarse model fit. These are guidance, never benchmark promises. */
final class ModelGuidance {
    enum DeviceFit { GOOD, DEMANDING, UNSUPPORTED, NOT_ENOUGH_RAM, NOT_ENOUGH_STORAGE }

    private ModelGuidance() {}

    static DeviceFit fit(ModelDescriptor model, ModelRecommendation.DeviceProfile device) {
        if (!device.arm64 || (model.requiresDotProduct && !device.dotProduct)
                || (model.requiresFp16 && !device.fp16)) return DeviceFit.UNSUPPORTED;
        if (device.totalRamBytes < model.minimumRamBytes) return DeviceFit.NOT_ENOUGH_RAM;
        if (device.freeStorageBytes < model.minimumFreeBytes) return DeviceFit.NOT_ENOUGH_STORAGE;
        return model.sizeTier() == ModelDescriptor.SizeTier.LARGE
                || model.sizeTier() == ModelDescriptor.SizeTier.VERY_LARGE
                ? DeviceFit.DEMANDING : DeviceFit.GOOD;
    }
}
