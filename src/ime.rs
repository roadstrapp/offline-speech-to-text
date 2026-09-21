use jni::objects::{JClass, JObject};
use jni::sys::{jboolean, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::Mutex;

use crate::voice_session::{self, VoiceSessionState};

static IME_STATE: Lazy<Mutex<Option<VoiceSessionState>>> = Lazy::new(|| Mutex::new(None));

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    let state = voice_session::init_session(env, service, voice_session::OWNER_IME);
    *IME_STATE.lock().unwrap() = Some(state);
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(mut state) = IME_STATE.lock().unwrap().take() {
        voice_session::cleanup_session(&mut state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_startRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        // The IME keyboard is manual tap-to-stop; no silence auto-stop.
        voice_session::start_recording(env, state, false);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_stopRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::stop_recording(env, state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_finishRecording(
    env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let mut guard = IME_STATE.lock().unwrap();
    let Some(state) = guard.as_mut() else {
        return JNI_FALSE;
    };
    if voice_session::end_recording_capture(state) {
        voice_session::transcribe_recording(env, state);
        JNI_TRUE
    } else {
        // An IME lifecycle transition can stop an auto-started session before
        // the audio callback's first buffer. Release the native owner without
        // reporting a false permission error; Java decides whether the new
        // editor should immediately auto-start another session.
        voice_session::cleanup_session(state);
        JNI_FALSE
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cancelRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::cancel_recording(env, state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_setRecordingPaused(
    _env: JNIEnv,
    _class: JClass,
    paused: jboolean,
) -> jboolean {
    let mut guard = IME_STATE.lock().unwrap();
    let success = guard
        .as_mut()
        .map(|state| voice_session::set_recording_paused(state, paused != JNI_FALSE))
        .unwrap_or(false);
    if success {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
