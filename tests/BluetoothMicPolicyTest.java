package dev.notune.transcribe;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class BluetoothMicPolicyTest {
    private static final int BUILTIN_MIC = 15;
    private static final int A2DP = 8;
    private static final int EARPIECE = 1;
    private static final int MODE_IN_COMMUNICATION = 3;
    private static final int MODE_IN_CALL = 2;

    public static void main(String[] args) {
        // Routing needs the opt-in, Android 12+, and no call owning the audio mode.
        check(BluetoothMicPolicy.mayRoute(31, true, BluetoothMicPolicy.MODE_NORMAL), "API 31 refused");
        check(BluetoothMicPolicy.mayRoute(37, true, BluetoothMicPolicy.MODE_NORMAL), "API 37 refused");
        check(!BluetoothMicPolicy.mayRoute(37, false, BluetoothMicPolicy.MODE_NORMAL), "default off ignored");
        check(!BluetoothMicPolicy.mayRoute(30, true, BluetoothMicPolicy.MODE_NORMAL), "pre-31 routed");
        check(!BluetoothMicPolicy.mayRoute(26, true, BluetoothMicPolicy.MODE_NORMAL), "minSdk routed");
        check(!BluetoothMicPolicy.mayRoute(37, true, MODE_IN_COMMUNICATION), "took over a VoIP call");
        check(!BluetoothMicPolicy.mayRoute(37, true, MODE_IN_CALL), "took over a phone call");

        BluetoothMicPolicy.Device earpiece = device(EARPIECE, "");
        BluetoothMicPolicy.Device sco = device(BluetoothMicPolicy.TYPE_BLUETOOTH_SCO, "XX:XX:XX:XX:0B:01");
        BluetoothMicPolicy.Device scoIn = device(BluetoothMicPolicy.TYPE_BLUETOOTH_SCO, "XX:XX:XX:XX:0B:01");
        BluetoothMicPolicy.Device le = device(BluetoothMicPolicy.TYPE_BLE_HEADSET, "XX:XX:XX:XX:0A:02");
        BluetoothMicPolicy.Device leIn = device(BluetoothMicPolicy.TYPE_BLE_HEADSET, "XX:XX:XX:XX:0A:02");
        BluetoothMicPolicy.Device leHearingIn =
                device(BluetoothMicPolicy.TYPE_BLE_HEARING_AID, "XX:XX:XX:XX:0A:02");
        BluetoothMicPolicy.Device builtin = device(BUILTIN_MIC, "bottom");

        // Nothing Bluetooth: the phone microphone.
        check(BluetoothMicPolicy.choose(list(earpiece), list(builtin)) == -1, "routed without Bluetooth");
        check(BluetoothMicPolicy.choose(Collections.emptyList(), Collections.emptyList()) == -1,
                "empty lists routed");
        // A media-only link is not a microphone.
        check(BluetoothMicPolicy.choose(list(earpiece, device(A2DP, "XX:XX:XX:XX:0B:01")),
                list(builtin, scoIn)) == -1, "A2DP chosen");
        // A communication device without a matching input keeps the phone mic.
        check(BluetoothMicPolicy.choose(list(earpiece, le), list(builtin)) == -1,
                "LE output without microphone chosen");
        check(BluetoothMicPolicy.choose(list(earpiece, sco),
                list(builtin, device(BluetoothMicPolicy.TYPE_BLUETOOTH_SCO, "XX:XX:XX:XX:00:00"))) == -1,
                "another headset's microphone matched");
        // Classic hands-free and LE Audio headsets.
        check(BluetoothMicPolicy.choose(list(earpiece, sco), list(builtin, scoIn)) == 1, "SCO not chosen");
        check(BluetoothMicPolicy.choose(list(earpiece, le), list(builtin, leIn)) == 1, "LE not chosen");
        // Android 17 LE hearing-aid input pairs with its LE output.
        check(BluetoothMicPolicy.choose(list(earpiece, le), list(builtin, leHearingIn)) == 1,
                "LE hearing aid input not matched");
        check(BluetoothMicPolicy.choose(
                list(device(BluetoothMicPolicy.TYPE_BLE_HEARING_AID, "XX:XX:XX:XX:0A:02")),
                list(leHearingIn)) == 0, "LE hearing aid not chosen");
        // An SCO input never satisfies an LE output, nor the reverse.
        check(BluetoothMicPolicy.choose(list(le),
                list(device(BluetoothMicPolicy.TYPE_BLUETOOTH_SCO, "XX:XX:XX:XX:0A:02"))) == -1,
                "cross-family input matched");
        // An LE hearing-aid set may list its input under the other side's address.
        check(BluetoothMicPolicy.choose(list(earpiece, le),
                list(builtin, device(BluetoothMicPolicy.TYPE_BLE_HEADSET, "XX:XX:XX:XX:0A:03"))) == 1,
                "LE input of the other side not accepted");
        check(BluetoothMicPolicy.matchingInput(le, list(
                device(BluetoothMicPolicy.TYPE_BLE_HEADSET, "XX:XX:XX:XX:0A:03"), leIn)) == 1,
                "exact LE address not preferred");
        check(BluetoothMicPolicy.matchingInput(le,
                list(device(BluetoothMicPolicy.TYPE_BLE_HEADSET, "xx:xx:xx:xx:0a:02"))) == 0,
                "address compared case-sensitively");
        // With both connected, LE Audio wins regardless of list order.
        check(BluetoothMicPolicy.choose(list(earpiece, sco, le), list(builtin, scoIn, leIn)) == 2,
                "LE not preferred over SCO");
        check(BluetoothMicPolicy.choose(list(le, sco), list(scoIn, leIn)) == 0,
                "LE not preferred when listed first");
        // Null addresses are treated as empty and compared exactly.
        check(BluetoothMicPolicy.choose(list(device(BluetoothMicPolicy.TYPE_BLUETOOTH_SCO, null)),
                list(device(BluetoothMicPolicy.TYPE_BLUETOOTH_SCO, ""))) == 0, "null address mismatch");
        System.out.println("BluetoothMicPolicyTest passed");
    }

    private static BluetoothMicPolicy.Device device(int type, String address) {
        return new BluetoothMicPolicy.Device(type, address);
    }

    private static List<BluetoothMicPolicy.Device> list(BluetoothMicPolicy.Device... devices) {
        return Arrays.asList(devices);
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
