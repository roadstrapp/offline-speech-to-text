use jni::objects::{JClass, JObject, JValue};
use jni::sys::jlong;
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::Mutex;

use crate::voice_session::{self, VoiceSessionState};

static FOREGROUND_STATE: Lazy<Mutex<Option<VoiceSessionState>>> = Lazy::new(|| Mutex::new(None));

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_ForegroundRecordingService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    let state = voice_session::init_session(env, service, voice_session::OWNER_FOREGROUND);
    if let Some(mut old) = FOREGROUND_STATE.lock().unwrap().replace(state) {
        voice_session::cleanup_session(&mut old);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_ForegroundRecordingService_cleanupNative(
    env: JNIEnv,
    caller: JObject,
) {
    let mut guard = FOREGROUND_STATE.lock().unwrap();
    if guard
        .as_ref()
        .is_some_and(|s| env.is_same_object(&s.target_ref, &caller).unwrap_or(false))
    {
        if let Some(mut state) = guard.take() {
            voice_session::cleanup_session(&mut state);
        }
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_ForegroundRecordingService_startRecording(
    env: JNIEnv,
    _class: JClass,
    generation: jlong,
    attempt: jlong,
) {
    let (result, jvm, target) = {
        let mut guard = FOREGROUND_STATE.lock().unwrap();
        let Some(state) = guard.as_mut() else {
            return;
        };
        let jvm = state.jvm.clone();
        let target = state.target_ref.clone();
        let result = voice_session::start_recording_guarded(env, state, generation, attempt);
        (result, jvm, target)
    };
    if let Ok(mut callback_env) = jvm.attach_current_thread() {
        let _ = callback_env.call_method(
            target.as_obj(),
            "onNativeStartResult",
            "(JJI)V",
            &[
                JValue::Long(generation),
                JValue::Long(attempt),
                JValue::Int(result),
            ],
        );
    };
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_ForegroundRecordingService_endRecordingCapture(
    _env: JNIEnv,
    _class: JClass,
) -> jni::sys::jboolean {
    if let Some(state) = FOREGROUND_STATE.lock().unwrap().as_mut() {
        return if voice_session::end_recording_capture(state) {
            1
        } else {
            0
        };
    }
    0
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_ForegroundRecordingService_beginTranscription(
    env: JNIEnv,
    _class: JClass,
) {
    if let Some(state) = FOREGROUND_STATE.lock().unwrap().as_mut() {
        voice_session::transcribe_recording(env, state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_ForegroundRecordingService_cancelRecording(
    env: JNIEnv,
    _class: JClass,
) {
    if let Some(state) = FOREGROUND_STATE.lock().unwrap().as_mut() {
        voice_session::cancel_recording(env, state);
    }
}
