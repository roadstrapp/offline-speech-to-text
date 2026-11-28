use std::sync::Mutex;

use jni::objects::{JClass, JObject};
use jni::JNIEnv;
use once_cell::sync::Lazy;

use crate::voice_session::{self, VoiceSessionState};

static RECOG_STATE: Lazy<Mutex<Option<VoiceSessionState>>> = Lazy::new(|| Mutex::new(None));

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RecognizeActivity_initNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    let state = voice_session::init_session(env, activity, voice_session::OWNER_ACTIVITY);
    if let Some(mut old) = RECOG_STATE.lock().unwrap().replace(state) {
        voice_session::cleanup_session(&mut old);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RecognizeActivity_cleanupNative(
    env: JNIEnv,
    caller: JObject,
) {
    let mut guard = RECOG_STATE.lock().unwrap();
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
pub unsafe extern "system" fn Java_dev_notune_transcribe_RecognizeActivity_startRecording(
    env: JNIEnv,
    _class: JClass,
    auto_stop: jni::sys::jboolean,
    trailing_pause_ms: jni::sys::jint,
) {
    let mut guard = RECOG_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::start_recording(env, state, auto_stop != 0, trailing_pause_ms);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RecognizeActivity_stopRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = RECOG_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::stop_recording(env, state);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RecognizeActivity_cancelRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = RECOG_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        crate::voice_session::cancel_recording(env, state);
    }
}
