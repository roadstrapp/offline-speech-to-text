package dev.notune.transcribe;

import java.util.List;

/**
 * Android-independent choice of a Bluetooth microphone for OSTT capture.
 *
 * Integers mirror {@code android.media.AudioDeviceInfo} and
 * {@code android.media.AudioManager} constants so the policy runs in host
 * tests. A communication device qualifies only when a matching input of the
 * same link family (LE Audio or classic hands-free) and address exists: an LE
 * Audio device without a microphone, or a headset whose hands-free profile is
 * unavailable, keeps the phone mic. LE hearing aids may report their output
 * and input under different LE types, so the family, not the exact type, is
 * compared.
 */
final class BluetoothMicPolicy {
    static final int TYPE_BLUETOOTH_SCO = 7;
    static final int TYPE_BLE_HEADSET = 26;
    /** Android 17 (API 37) reports LE Audio hearing aids with their own type. */
    static final int TYPE_BLE_HEARING_AID = 33;
    static final int MODE_NORMAL = 0;
    static final int API_COMMUNICATION_DEVICE = 31;

    private BluetoothMicPolicy() {}

    /** One audio device, reduced to the fields the policy compares. */
    static final class Device {
        final int type;
        final String address;

        Device(int type, String address) {
            this.type = type;
            this.address = address == null ? "" : address;
        }
    }

    /**
     * Routing is attempted only on Android 12+ (public communication-device
     * API), with the setting enabled, and while no call or other
     * communication session owns the audio mode; OSTT must never move another
     * app's call to a different device.
     */
    static boolean mayRoute(int sdkInt, boolean enabled, int audioMode) {
        return enabled && sdkInt >= API_COMMUNICATION_DEVICE && audioMode == MODE_NORMAL;
    }

    /**
     * Index of the communication device to select, or -1 for the phone
     * microphone. LE Audio (including hearing aids) is preferred over a
     * classic hands-free headset: it is the newer link with the better
     * microphone path, and both being available means two devices connected.
     */
    static int choose(List<Device> communicationDevices, List<Device> inputs) {
        return choose(communicationDevices, inputs, java.util.Collections.<Device>emptyList());
    }

    /**
     * As {@link #choose(List, List)}, but the microphone of the device media
     * plays to comes first: with a car's hands-free and headphones both
     * connected (Android Auto with audio left on the phone), the user listens
     * on the headphones and expects their microphone, not the car's, and
     * Android lists the communication devices in no useful order.
     * {@code mediaOutputs} are the devices
     * {@code AudioManager.getAudioDevicesForAttributes(USAGE_MEDIA)} reports.
     */
    static int choose(List<Device> communicationDevices, List<Device> inputs, List<Device> mediaOutputs) {
        for (int index = 0; index < communicationDevices.size(); index++) {
            Device candidate = communicationDevices.get(index);
            if (!isBluetoothMicType(candidate.type) || !hasMatchingInput(candidate, inputs)) continue;
            if (candidate.address.isEmpty()) continue;
            for (Device media : mediaOutputs) {
                if (candidate.address.equalsIgnoreCase(media.address)) return index;
            }
        }
        int sco = -1;
        for (int index = 0; index < communicationDevices.size(); index++) {
            Device candidate = communicationDevices.get(index);
            if (!isBluetoothMicType(candidate.type) || !hasMatchingInput(candidate, inputs)) {
                continue;
            }
            if (isLeAudio(candidate.type)) return index;
            if (sco < 0) sco = index;
        }
        return sco;
    }

    static boolean isBluetoothMicType(int type) {
        return isLeAudio(type) || type == TYPE_BLUETOOTH_SCO;
    }

    private static boolean isLeAudio(int type) {
        return type == TYPE_BLE_HEADSET || type == TYPE_BLE_HEARING_AID;
    }

    /**
     * Index of the input belonging to {@code candidate}, or -1 when it has no
     * microphone. The same address is preferred. For LE Audio any LE input is
     * accepted as well: a binaural hearing-aid set can list its output and
     * input under the addresses of different sides, and only one LE Audio
     * group carries a call at a time. A classic headset's input must match
     * exactly, since another headset's SCO microphone would not follow it.
     */
    static int matchingInput(Device candidate, List<Device> inputs) {
        int sameFamily = -1;
        for (int index = 0; index < inputs.size(); index++) {
            Device input = inputs.get(index);
            boolean family = isLeAudio(candidate.type)
                    ? isLeAudio(input.type) : input.type == candidate.type;
            if (!family) continue;
            if (input.address.equalsIgnoreCase(candidate.address)) return index;
            if (sameFamily < 0 && isLeAudio(candidate.type)) sameFamily = index;
        }
        return sameFamily;
    }

    private static boolean hasMatchingInput(Device candidate, List<Device> inputs) {
        return matchingInput(candidate, inputs) >= 0;
    }
}
