use crate::engine;
use jni::objects::JObject;
use jni::JNIEnv;
use std::sync::Arc;

/// Called after the user changes the model selection (or language hint):
/// drops the current engine and reloads with the new selection, reporting
/// progress via the activity's `onStatusUpdate` callback.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_ModelsActivity_reloadModelNative(
    env: JNIEnv,
    _this: JObject,
    activity: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Off),
    );
    let vm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let activity_ref = match env.new_global_ref(&activity) {
        Ok(r) => r,
        Err(_) => return,
    };
    std::thread::spawn(move || {
        engine::reset();
        let _ = engine::ensure_loaded_from_thread(&vm, &activity_ref);
    });
}

/// Read-only identity check; does not load, reset, or log model paths.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_ModelsActivity_loadedModelPathNative(
    env: JNIEnv,
    _this: JObject,
) -> jni::sys::jstring {
    engine::loaded_model_path()
        .and_then(|path| env.new_string(path.to_string_lossy().as_ref()).ok())
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Qualification readback of the actual loaded generation, threads and language-ID policy.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_ModelsActivity_loadedModelConfigurationNative(
    env: JNIEnv,
    _this: JObject,
) -> jni::sys::jstring {
    engine::loaded_model_configuration()
        .and_then(|configuration| env.new_string(configuration).ok())
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
