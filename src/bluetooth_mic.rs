//! Optional Bluetooth microphone routing for OSTT's own capture paths.
//!
//! The routing decision lives in `BluetoothMicRoute.java` because it needs the
//! Android `AudioManager`. This side only ties the routing request to the
//! lifetime of one native input stream: acquire before the stream opens, and
//! release when the guard drops. The guard is stored after the stream inside
//! `SendStream`, so every stop, cancel, error, cleanup, and replacement path
//! closes the microphone first and then clears the communication device.
//! Process death needs no cleanup here: AudioService drops a dead client's
//! request through its binder death notification.

use std::sync::Arc;

use jni::objects::{GlobalRef, JObject, JValue};
use jni::JNIEnv;

const ROUTE_CLASS: &str = "dev/notune/transcribe/BluetoothMicRoute";

/// A held communication-device request. Dropping it releases the request on
/// whichever thread drops the stream; the class reference is cached because a
/// native-spawned thread cannot resolve app classes by name.
pub struct RouteGuard {
    jvm: Arc<jni::JavaVM>,
    class: GlobalRef,
    token: i64,
    input_device_id: i32,
}

/// What a stream opened for the route reports about itself.
pub struct OpenedStream {
    pub device_id: i32,
    pub session_id: i32,
    pub sample_rate: i32,
    pub channels: i32,
    /// 16 kHz mono float, as the capture callbacks expect.
    pub exact_format: bool,
}

impl RouteGuard {
    pub fn token(&self) -> i64 {
        self.token
    }

    pub fn class(&self) -> &GlobalRef {
        &self.class
    }

    /// AudioDeviceInfo id of the Bluetooth input, or 0 to let the route
    /// (the communication device) choose it.
    pub fn input_device_id(&self) -> i32 {
        self.input_device_id
    }

    /// Ask Java whether the opened stream really records from a Bluetooth
    /// input (it also logs the outcome). False means the stream must be
    /// closed and capture reopened on the phone microphone.
    pub fn confirm(&self, env: &mut JNIEnv, opened: &OpenedStream) -> bool {
        let accepted = env
            .call_static_method(
                &self.class,
                "confirm",
                "(JIIIIZ)Z",
                &[
                    JValue::Long(self.token),
                    JValue::Int(opened.device_id),
                    JValue::Int(opened.session_id),
                    JValue::Int(opened.sample_rate),
                    JValue::Int(opened.channels),
                    JValue::Bool(opened.exact_format as u8),
                ],
            )
            .and_then(|value| value.z())
            .unwrap_or(false);
        if env.exception_check().unwrap_or(true) {
            let _ = env.exception_clear();
            return false;
        }
        accepted
    }
}

impl Drop for RouteGuard {
    fn drop(&mut self) {
        if let Ok(mut env) = self.jvm.attach_current_thread() {
            let result = env.call_static_method(
                &self.class,
                "release",
                "(J)V",
                &[JValue::Long(self.token)],
            );
            if result.is_err() || env.exception_check().unwrap_or(true) {
                let _ = env.exception_clear();
            }
        }
    }
}

/// The Java side's class, resolved once on a thread entered from Java so a
/// native watchdog thread can call it later.
pub fn route_class(env: &mut JNIEnv) -> Option<GlobalRef> {
    let class = match env.find_class(ROUTE_CLASS) {
        Ok(class) => class,
        Err(_) => {
            let _ = env.exception_clear();
            return None;
        }
    };
    let global = env.new_global_ref(&class).ok();
    if global.is_none() {
        let _ = env.exception_clear();
    }
    global
}

/// Route capture to a connected Bluetooth microphone when the user enabled it
/// (or chose it for this recording). Returns `None` for the phone microphone:
/// setting off, unsupported Android version, no Bluetooth input, a call in
/// progress, or any routing failure. `again` re-routes a recording that is
/// already on Bluetooth (after a dead stream), whatever the setting says.
pub fn acquire(
    env: &mut JNIEnv,
    jvm: &Arc<jni::JavaVM>,
    class: &GlobalRef,
    context: &JObject,
    again: bool,
    choice: Option<i32>,
) -> Option<RouteGuard> {
    let token = if let Some(choice) = choice.filter(|_| !again) {
        env.call_static_method(class, "acquireForStart", "(Landroid/content/Context;I)J",
            &[JValue::Object(context), JValue::Int(choice)])
    } else {
        env.call_static_method(class, if again { "reacquire" } else { "acquire" },
            "(Landroid/content/Context;)J", &[JValue::Object(context)])
    }.and_then(|value| value.j());
    if env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
        return None;
    }
    let token = token.ok().filter(|token| *token > 0)?;
    let input_device_id = env
        .call_static_method(class, "inputDeviceId", "(J)I", &[JValue::Long(token)])
        .and_then(|value| value.i())
        .unwrap_or(0);
    if env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
    }
    // A missing device id is fine: the stream's VOICE_COMMUNICATION preset
    // follows the communication device, and `confirm` checks the result.
    Some(RouteGuard {
        jvm: jvm.clone(),
        class: class.clone(),
        token,
        input_device_id,
    })
}

/// A line under the OSTT-BT log tag (no audio or text content).
pub fn log(env: &mut JNIEnv, class: &GlobalRef, message: &str) {
    if let Ok(message) = env.new_string(message) {
        let _ = env.call_static_method(
            class,
            "log",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&message)],
        );
    }
    if env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
    }
}

/// The Bluetooth stream delivers sound: the recording screen stops saying
/// it is waiting for the Bluetooth microphone.
pub fn notify_live(env: &mut JNIEnv, class: &GlobalRef) {
    let _ = env.call_static_method(class, "onCaptureLive", "()V", &[]);
    if env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
    }
}

/// Recovery is closing and reopening streams: the recording screen keeps
/// its state until it ends (no Bluetooth/phone flicker).
pub fn set_recovering(env: &mut JNIEnv, class: &GlobalRef, recovering: bool) {
    let _ = env.call_static_method(class, "setRecovering", "(Z)V", &[JValue::Bool(recovering as u8)]);
    if env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
    }
}

/// The Bluetooth microphone failed for this recording and capture moved to
/// the phone microphone: Java plays the cue and updates the recording screen.
pub fn notify_fallback(env: &mut JNIEnv, class: &GlobalRef, reason: &str) {
    if let Ok(reason) = env.new_string(reason) {
        let _ = env.call_static_method(
            class,
            "onCaptureFallback",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&reason)],
        );
    }
    if env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
    }
}

/// Bind a one-time UI choice before dispatching asynchronous capture setup.
pub fn take_start_override(env: &mut JNIEnv, class: &GlobalRef) -> i32 {
    let choice = env.call_static_method(class, "takeStartOverride", "()I", &[]).and_then(|v| v.i()).unwrap_or(-1);
    if env.exception_check().unwrap_or(true) { let _ = env.exception_clear(); }
    choice
}
