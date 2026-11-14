package dev.notune.transcribe;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Optional "Use Bluetooth microphone when connected" routing (default off).
 *
 * Native capture calls {@link #acquire} immediately before opening its input
 * stream, {@link #confirm} once the stream is open, and {@link #release} after
 * closing it (see {@code bluetooth_mic.rs}), so the request lives exactly as
 * long as one microphone stream.
 *
 * The order matters, and follows what records reliably from LE Audio hearing
 * aids: the headset is made the communication device first, and the stream is
 * opened only after Android reports that device as active and a short settle
 * has passed (the platform reports it before the LE Audio link has finished
 * reconfiguring to its bidirectional call stream). The stream is then opened
 * with the VOICE_COMMUNICATION preset, which follows the communication device,
 * and additionally pinned to the headset's input. Opening with the default
 * voice-recognition preset, or before the route is up, leaves the capture on
 * the phone microphone even though the headset sits in call mode.
 *
 * {@link #confirm} checks the device the stream actually opened on; anything
 * other than a Bluetooth input releases the route and native code reopens on
 * the phone microphone, so the recording screen can always say which
 * microphone really carries the recording. Every decision is logged under the
 * {@value #TAG} tag (device types only, never audio).
 *
 * The request is tied to the application context's AudioManager because the
 * platform keys it to that manager's client token; clearing through another
 * manager would leave it in place. AudioService drops a dead process's request
 * itself, so a crash cannot leave the headset in call mode.
 */
final class BluetoothMicRoute {
    static final String MARKER = "bluetooth_mic";
    static final String TAG = "OSTT-BT";
    /** How long a start waits for the headset to become the communication device. */
    private static final long ROUTE_TIMEOUT_MS = 3_000L;
    /** After Android reports the route, before the microphone opens. */
    private static final long ROUTE_SETTLE_MS = 500L;
    private static final long ROUTE_POLL_MS = 20L;

    private static final Object LOCK = new Object();
    private static long nextToken = 1L;
    private static long activeToken;
    private static int activeInputId;
    private static AudioManager activeManager;
    /** True once the open stream was verified to run on a Bluetooth input. */
    private static boolean confirmed;
    private static String routedName = "";
    private static int activeSessionId;
    private static AudioManager.AudioRecordingCallback recordingCallback;
    /** One-shot choice for the next capture start (the recording screen's switch); null = setting. */
    private static Boolean nextStartOverride;
    /** Set when a recording moved from a dead Bluetooth stream to the phone mic. */
    private static boolean fallbackNotice;
    /** While native recovery swaps streams, listeners wait for the outcome. */
    private static boolean recovering;
    /** The routed Bluetooth stream has delivered sound (its LE Audio setup is done). */
    private static boolean live;
    /**
     * When Bluetooth last gave only digital zeros (the aid's microphone off):
     * for a while new recordings start on the phone microphone instead of
     * losing their first seconds again. The recording screen's switch still
     * tries Bluetooth on request.
     */
    private static long zerosFallbackAt;
    private static final long ZEROS_COOLDOWN_MS = 3 * 60_000L;

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BluetoothMicRoute() {}

    static boolean isSupported() {
        return Build.VERSION.SDK_INT >= BluetoothMicPolicy.API_COMMUNICATION_DEVICE;
    }

    static boolean isEnabled(File filesDir) {
        return new File(filesDir, MARKER).exists();
    }

    /** Saves the setting (the "Switch and keep" choice on the recording screen). */
    static void setEnabled(File filesDir, boolean enabled) {
        File marker = new File(filesDir, MARKER);
        try {
            if (enabled) {
                if (!marker.exists()) marker.createNewFile();
            } else if (marker.exists()) {
                marker.delete();
            }
        } catch (java.io.IOException e) {
            Log.w(TAG, "could not save the Bluetooth microphone setting", e);
        }
    }

    /**
     * Use (true) or skip (false) the Bluetooth microphone for the next capture
     * start only, regardless of the setting. Consumed by that start.
     */
    static void overrideNextStart(boolean bluetooth) {
        synchronized (LOCK) {
            nextStartOverride = bluetooth;
        }
    }

    /** True while the open microphone stream really records from a Bluetooth input. */
    static boolean isRouted() {
        synchronized (LOCK) {
            return isRoutedLocked();
        }
    }

    /** Product name of the Bluetooth microphone carrying the recording, or "". */
    static String routedName() {
        synchronized (LOCK) {
            return isRoutedLocked() ? routedName : "";
        }
    }

    private static boolean isRoutedLocked() {
        return activeToken != 0L && confirmed;
    }

    /**
     * Whether a Bluetooth microphone could be used right now, so the recording
     * screen offers the Bluetooth / phone switch at all.
     */
    static boolean isBluetoothMicAvailable(Context context) {
        if (!isSupported() || context == null) return false;
        if (isRouted()) return true;
        try {
            return chooseApi31(context.getApplicationContext()) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Runs {@code listener} on the main thread whenever the routed microphone changes. */
    static void addListener(Runnable listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        synchronized (LOCK) {
            if (recovering) return;
        }
        for (Runnable listener : LISTENERS) MAIN.post(listener);
    }

    /**
     * Called from native code before the microphone opens. Returns a positive
     * token when the Bluetooth route is up and the stream should be opened for
     * it, else 0 (phone microphone). Blocks for up to a few seconds while the
     * headset switches to its call link. Never throws.
     */
    static long acquire(Context context) {
        return acquireForStart(context, takeStartOverride());
    }

    /** Snapshot synchronously for the exact capture before its setup worker starts. */
    static int takeStartOverride() {
        synchronized (LOCK) {
            int choice = nextStartOverride == null ? -1 : nextStartOverride ? 1 : 0;
            nextStartOverride = null;
            return choice;
        }
    }

    static long acquireForStart(Context context, int choice) {
        Boolean override = choice < 0 ? null : choice != 0;
        synchronized (LOCK) { fallbackNotice = false; }
        if (context == null) return 0L;
        if (!isSupported()) {
            Log.i(TAG, "start: phone microphone (Android " + Build.VERSION.SDK_INT + " < 12)");
            return 0L;
        }
        try {
            Context app = context.getApplicationContext();
            if (app == null) app = context;
            boolean wanted = override != null ? override : isEnabled(app.getFilesDir());
            if (!wanted) {
                Log.i(TAG, "start: phone microphone ("
                        + (override != null ? "chosen for this recording" : "setting off") + ")");
                return 0L;
            }
            long sinceZeros;
            synchronized (LOCK) {
                sinceZeros = zerosFallbackAt == 0L ? Long.MAX_VALUE
                        : SystemClock.elapsedRealtime() - zerosFallbackAt;
            }
            if (override == null && sinceZeros < ZEROS_COOLDOWN_MS) {
                Log.i(TAG, "start: phone microphone (Bluetooth gave only silence "
                        + (sinceZeros / 1000) + " s ago; the switch can still try it)");
                return 0L;
            }
            return acquireApi31(app);
        } catch (RuntimeException e) {
            // Includes a vendor SecurityException; fall back to the phone mic.
            Log.w(TAG, "start: routing failed, phone microphone", e);
            return 0L;
        }
    }

    /**
     * Called from native code when a Bluetooth stream of the current recording
     * died and is reopened once: route again whatever the setting says (the
     * recording is already on Bluetooth), with the full wait and settle.
     */
    static long reacquire(Context context) {
        if (context == null || !isSupported()) return 0L;
        try {
            Context app = context.getApplicationContext();
            return acquireApi31(app == null ? context : app);
        } catch (RuntimeException e) {
            Log.w(TAG, "reroute failed", e);
            return 0L;
        }
    }

    /**
     * Called from native code: an AudioRecord on the routed Bluetooth input of
     * {@code token}, or null. {@code handle} is native code's feed target.
     */
    static BluetoothAudioRecord openRecorder(long token, long handle) {
        AudioManager manager;
        int input;
        synchronized (LOCK) {
            if (token <= 0L || token != activeToken) return null;
            manager = activeManager;
            input = activeInputId;
        }
        return BluetoothAudioRecord.open(manager, input, handle);
    }

    /**
     * Debug only: with the marker file {@code bt_capture_aaudio} the first
     * Bluetooth stream uses AAudio, which reproduces the zero-sample failure
     * on LE Audio hearing aids and exercises the recovery.
     */
    static boolean preferAAudio(Context context) {
        return context != null && new File(context.getFilesDir(), "bt_capture_aaudio").exists();
    }

    /** Native capture diagnostics (frame timing, recovery); no audio or text. */
    static void log(String message) {
        Log.i(TAG, message);
    }

    /**
     * Called from native code: the Bluetooth microphone delivered nothing
     * usable, even after one reopen, so this recording continues on the phone
     * microphone (audio so far is kept). Plays a short falling cue so the
     * user does not keep talking into a dead headset, and lets the recording
     * screen show it.
     */
    static void onCaptureFallback(String reason) {
        Log.w(TAG, "fell back to the phone microphone: " + reason);
        synchronized (LOCK) {
            fallbackNotice = true;
            if (reason != null && reason.contains("zeros")) {
                zerosFallbackAt = SystemClock.elapsedRealtime();
            }
        }
        CaptureCue.playFallback();
        notifyListeners();
    }

    /** Called from native code once the Bluetooth stream delivers sound. */
    static void onCaptureLive() {
        synchronized (LOCK) {
            live = true;
        }
        notifyListeners();
    }

    /**
     * True while the recording is routed to Bluetooth but the headset has not
     * delivered sound yet (LE Audio sets up the microphone stream only when
     * recording starts, which takes a few seconds): words spoken now are lost.
     */
    static boolean isWaitingForSound() {
        synchronized (LOCK) {
            return isRoutedLocked() && !live;
        }
    }

    /** Called from native code around a stream swap; one update follows the end. */
    static void setRecovering(boolean active) {
        synchronized (LOCK) {
            recovering = active;
        }
        if (!active) notifyListeners();
    }

    /** Debug only: marker file {@code bt_observe_only} logs a dead stream but never recovers it. */
    static boolean observeOnly(Context context) {
        return context != null && new File(context.getFilesDir(), "bt_observe_only").exists();
    }

    /** Debug only: marker file {@code bt_simulate_zeros} mutes Bluetooth samples. */
    static boolean simulateZeros(Context context) {
        return context != null && new File(context.getFilesDir(), "bt_simulate_zeros").exists();
    }

    /** True once after {@link #onCaptureFallback}; for the recording screen's status. */
    static boolean takeFallbackNotice() {
        synchronized (LOCK) {
            boolean notice = fallbackNotice;
            fallbackNotice = false;
            return notice;
        }
    }

    /** AudioDeviceInfo id of the Bluetooth input for {@code token}, else 0 (let the route decide). */
    static int inputDeviceId(long token) {
        synchronized (LOCK) {
            return token > 0L && token == activeToken ? activeInputId : 0;
        }
    }

    /**
     * Called from native code once the routed stream is open, with what the
     * stream reports. Returns true when it records from a Bluetooth input in
     * the format the callbacks expect; false releases the route and native
     * code reopens on the phone microphone.
     */
    static boolean confirm(long token, int deviceId, int sessionId, int sampleRate,
                           int channels, boolean exactFormat) {
        AudioManager manager;
        synchronized (LOCK) {
            if (token <= 0L || token != activeToken) return false;
            manager = activeManager;
        }
        if (manager == null || !isSupported()) return false;
        AudioDeviceInfo device = null;
        try {
            for (AudioDeviceInfo input : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (input.getId() == deviceId) device = input;
            }
        } catch (RuntimeException ignored) { }
        boolean bluetooth = device != null && BluetoothMicPolicy.isBluetoothMicType(device.getType());
        Log.i(TAG, "stream opened on " + describe(device) + " (id " + deviceId
                + ", requested " + inputDeviceId(token) + ", " + sampleRate + " Hz, "
                + channels + " ch, session " + sessionId + ")");
        if (!bluetooth || !exactFormat) {
            Log.w(TAG, "routed stream rejected (" + (!bluetooth ? "not a Bluetooth input"
                    : "format differs") + "); falling back to the phone microphone");
            return false;
        }
        synchronized (LOCK) {
            if (token != activeToken) return false;
            confirmed = true;
            live = false;
            routedName = productName(device);
            activeSessionId = sessionId;
        }
        Log.i(TAG, "recording from Bluetooth microphone " + describe(device));
        watchRecording(manager, sessionId, token);
        notifyListeners();
        return true;
    }

    /** Called from native code after the microphone stream closed. Idempotent. */
    static void release(long token) {
        if (token <= 0L) return;
        synchronized (LOCK) {
            if (token != activeToken) return;
            clearActiveLocked();
        }
        notifyListeners();
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static long acquireApi31(Context app) {
        AudioManager manager = app.getSystemService(AudioManager.class);
        if (manager == null) return 0L;
        if (!BluetoothMicPolicy.mayRoute(Build.VERSION.SDK_INT, true, manager.getMode())) {
            Log.i(TAG, "start: phone microphone (audio mode " + manager.getMode()
                    + ": a call or another app's communication session)");
            return 0L;
        }
        AudioDeviceInfo target = chooseApi31(app);
        if (target == null) {
            Log.i(TAG, "start: phone microphone (no Bluetooth microphone among "
                    + describeAll(manager.getAvailableCommunicationDevices()) + ")");
            return 0L;
        }

        long token = 0L;
        boolean already;
        try {
        long started = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            // One capture per process; a stale request from an earlier stream
            // is refused rather than stacked.
            if (activeToken != 0L) return 0L;
            AudioDeviceInfo current = manager.getCommunicationDevice();
            already = current != null && current.getId() == target.getId();
            if (!manager.setCommunicationDevice(target)) {
                Log.w(TAG, "start: phone microphone (Android refused " + describe(target) + ")");
                return 0L;
            }
            token = nextToken++;
            activeToken = token;
            activeInputId = 0;
            activeManager = manager;
            confirmed = false;
        }
        long deadline = started + ROUTE_TIMEOUT_MS;
        boolean reached = already;
        while (!reached) {
            AudioDeviceInfo current = manager.getCommunicationDevice();
            reached = current != null && current.getId() == target.getId();
            if (reached || SystemClock.elapsedRealtime() >= deadline) break;
            SystemClock.sleep(ROUTE_POLL_MS);
        }
        if (!reached) {
            Log.w(TAG, "start: phone microphone (" + describe(target) + " did not become the "
                    + "communication device within " + ROUTE_TIMEOUT_MS + " ms)");
            release(token);
            return 0L;
        }
        // The platform reports the device before the LE Audio link has
        // reconfigured to its call stream; a stream opened at once can still
        // land on the phone microphone.
        if (!already) SystemClock.sleep(ROUTE_SETTLE_MS);

        // The input side is looked up after the route is up, when it is sure to be listed.
        AudioDeviceInfo[] inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        int input = BluetoothMicPolicy.matchingInput(
                new BluetoothMicPolicy.Device(target.getType(), target.getAddress()), describe(inputs));
        int inputId = input >= 0 ? inputs[input].getId() : 0;
        synchronized (LOCK) {
            // A concurrent release wins; the stream then opens on the phone mic.
            if (activeToken != token) return 0L;
            activeInputId = inputId;
        }
        Log.i(TAG, "start: routed to " + describe(target) + (already ? " (already active)" : "")
                + " in " + (SystemClock.elapsedRealtime() - started) + " ms; input "
                + (input >= 0 ? describe(inputs[input]) + " id " + inputId
                        : "not listed, following the route"));
        return token;
        } catch (RuntimeException e) {
            if (token != 0L) release(token);
            throw e;
        }
    }

    /** The best Bluetooth communication device with a microphone, or null. */
    @RequiresApi(Build.VERSION_CODES.S)
    private static AudioDeviceInfo chooseApi31(Context app) {
        AudioManager manager = app.getSystemService(AudioManager.class);
        if (manager == null) return null;
        List<AudioDeviceInfo> candidates = manager.getAvailableCommunicationDevices();
        AudioDeviceInfo[] inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        int chosen = BluetoothMicPolicy.choose(describe(candidates), describe(inputs), describe(mediaOutputs(manager)));
        return chosen < 0 ? null : candidates.get(chosen);
    }

    /**
     * Logs where Android really delivers the recording and whether it silences
     * it, and drops the Bluetooth label if the input moves elsewhere (the
     * headset disconnected) so the recording screen keeps telling the truth.
     */
    private static void watchRecording(AudioManager manager, int sessionId, long token) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        AudioManager.AudioRecordingCallback callback = new AudioManager.AudioRecordingCallback() {
            private int lastDevice = -1;
            private boolean lastSilenced;

            @Override
            public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                for (AudioRecordingConfiguration config : configs) {
                    if (config.getClientAudioSessionId() != sessionId) continue;
                    AudioDeviceInfo device = config.getAudioDevice();
                    int id = device == null ? 0 : device.getId();
                    boolean silenced = config.isClientSilenced();
                    if (id == lastDevice && silenced == lastSilenced) return;
                    lastDevice = id;
                    lastSilenced = silenced;
                    Log.i(TAG, "recording input now " + describe(device)
                            + (silenced ? " (silenced by Android)" : ""));
                    boolean bluetooth = device != null
                            && BluetoothMicPolicy.isBluetoothMicType(device.getType()) && !silenced;
                    boolean changed;
                    synchronized (LOCK) {
                        boolean mine = activeToken != 0L && activeSessionId == sessionId;
                        changed = mine && confirmed != bluetooth;
                        if (mine) {
                            confirmed = bluetooth;
                            if (bluetooth) routedName = productName(device);
                        }
                    }
                    if (changed) notifyListeners();
                    return;
                }
            }
        };
        try {
            synchronized (LOCK) {
                if (token != activeToken || recordingCallback != null) return;
                manager.registerAudioRecordingCallback(callback, MAIN);
                recordingCallback = callback;
            }
            callback.onRecordingConfigChanged(manager.getActiveRecordingConfigurations());
        } catch (RuntimeException ignored) { }
    }

    private static void clearActive() {
        boolean wasActive;
        synchronized (LOCK) {
            wasActive = activeToken != 0L;
            clearActiveLocked();
        }
        if (wasActive) notifyListeners();
    }

    private static void clearActiveLocked() {
        AudioManager manager = activeManager;
        AudioManager.AudioRecordingCallback callback = recordingCallback;
        boolean had = activeToken != 0L;
        activeManager = null;
        activeToken = 0L;
        activeInputId = 0;
        confirmed = false;
        live = false;
        recovering = false;
        routedName = "";
        activeSessionId = 0;
        recordingCallback = null;
        if (manager == null || !isSupported()) return;
        if (callback != null) {
            try {
                manager.unregisterAudioRecordingCallback(callback);
            } catch (RuntimeException ignored) { }
        }
        try {
            manager.clearCommunicationDevice();
            if (had) Log.i(TAG, "route released (communication device cleared)");
        } catch (RuntimeException ignored) { }
    }

    private static String productName(AudioDeviceInfo device) {
        CharSequence name = device == null ? null : device.getProductName();
        return name == null ? "" : name.toString();
    }

    private static String describe(AudioDeviceInfo device) {
        if (device == null) return "none";
        return "type " + device.getType() + ", "
                + (device.isSource() ? "input" : "output");
    }

    private static String describeAll(List<AudioDeviceInfo> devices) {
        if (devices == null || devices.isEmpty()) return "[]";
        StringBuilder out = new StringBuilder("[");
        for (AudioDeviceInfo device : devices) {
            if (out.length() > 1) out.append(", ");
            out.append(describe(device));
        }
        return out.append(']').toString();
    }

    /** Where media plays now (Android 13+), to prefer that headset's microphone. */
    private static List<AudioDeviceInfo> mediaOutputs(AudioManager manager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return new ArrayList<>();
        try {
            return manager.getAudioDevicesForAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA).build());
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }

    private static List<BluetoothMicPolicy.Device> describe(List<AudioDeviceInfo> devices) {
        List<BluetoothMicPolicy.Device> described = new ArrayList<>();
        if (devices == null) return described;
        for (AudioDeviceInfo device : devices) {
            described.add(new BluetoothMicPolicy.Device(device.getType(),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? device.getAddress() : ""));
        }
        return described;
    }

    private static List<BluetoothMicPolicy.Device> describe(AudioDeviceInfo[] devices) {
        List<BluetoothMicPolicy.Device> described = new ArrayList<>();
        if (devices == null) return described;
        for (AudioDeviceInfo device : devices) {
            described.add(new BluetoothMicPolicy.Device(device.getType(),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? device.getAddress() : ""));
        }
        return described;
    }
}
