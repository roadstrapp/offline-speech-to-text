package dev.notune.transcribe;

import android.content.Context;
import android.os.IBinder;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * The Bluetooth / phone microphone switch on the recording screens (voice
 * keyboard and popup). It first reports which microphone really carries the
 * recording ({@link BluetoothMicRoute#isRouted()}, the verified input, not
 * the setting), and is offered only while a Bluetooth microphone is usable.
 * Tapping it asks before switching, because a switch discards the recording
 * so far and starts a new one on the other microphone.
 */
final class MicSourceToggle {
    /** Discards the current recording and starts again on the chosen microphone. */
    interface Restart {
        void restart(boolean bluetooth);
    }

    private MicSourceToggle() {}

    static void refresh(MaterialButton button, Context context, boolean recording) {
        if (button == null) return;
        boolean show = recording && BluetoothMicRoute.isBluetoothMicAvailable(context);
        button.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        boolean bluetooth = BluetoothMicRoute.isRouted();
        button.setText(bluetooth ? R.string.mic_toggle_bluetooth : R.string.mic_toggle_phone);
        button.setIconResource(bluetooth ? R.drawable.ic_bluetooth : R.drawable.ic_phone_android);
        button.setContentDescription(context.getString(bluetooth
                ? R.string.mic_toggle_desc_bluetooth : R.string.mic_toggle_desc_phone));
    }

    /**
     * Shows the confirmation. {@code imeWindowToken} attaches the dialog to an
     * input method window (null for an activity).
     */
    static AlertDialog confirm(Context themed, IBinder imeWindowToken, Restart restart) {
        boolean toBluetooth = !BluetoothMicRoute.isRouted();
        AlertDialog dialog = new MaterialAlertDialogBuilder(themed)
                .setTitle(toBluetooth ? R.string.mic_switch_to_bluetooth_title
                        : R.string.mic_switch_to_phone_title)
                .setMessage(R.string.mic_switch_message)
                .setPositiveButton(R.string.mic_switch_once,
                        (d, which) -> restart.restart(toBluetooth))
                .setNeutralButton(toBluetooth ? R.string.mic_switch_keep_bluetooth
                        : R.string.mic_switch_keep_phone, (d, which) -> {
                    BluetoothMicRoute.setEnabled(themed.getFilesDir(), toBluetooth);
                    restart.restart(toBluetooth);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        if (imeWindowToken != null) {
            Window window = dialog.getWindow();
            if (window != null) {
                WindowManager.LayoutParams params = window.getAttributes();
                params.token = imeWindowToken;
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
                window.setAttributes(params);
                window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            }
        }
        dialog.show();
        return dialog;
    }
}
