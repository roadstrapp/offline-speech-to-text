//! JNI surface of `app.roadstr.offlinestt.OfflineSttNative`.
//!
//! Engines are addressed by opaque, never-reused `long` ids kept in a registry,
//! so a stale id from a buggy caller is an `IllegalStateException`, not a use
//! after free. Failures are thrown as standard JDK exceptions only (no app
//! classes), so R8/ProGuard renaming of the Kotlin side cannot break them.
//! Every entry point is wrapped in `catch_unwind`: a panic becomes a
//! `RuntimeException`, never an abort of the host process.

use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, TryLockError};

use jni::objects::{JClass, JFloatArray, JObject, JObjectArray, JString};
use jni::sys::{jint, jlong, jobjectArray, jstring};
use jni::JNIEnv;
use transcribe_cpp::CancelToken;

use crate::core::{EngineError, SttEngine};

struct Handle {
    engine: Mutex<SttEngine>,
    cancel: CancelToken,
}

fn registry() -> &'static Mutex<HashMap<i64, Arc<Handle>>> {
    static REGISTRY: OnceLock<Mutex<HashMap<i64, Arc<Handle>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

static NEXT_ID: AtomicI64 = AtomicI64::new(1);

enum ApiError {
    /// `java.io.IOException`: the model is missing or was rejected.
    Io(String),
    /// `java.lang.IllegalArgumentException`: unusable audio or arguments.
    InvalidArgument(String),
    /// `java.lang.IllegalStateException`: closed or unknown engine.
    State(String),
    /// `java.util.concurrent.RejectedExecutionException`: another call is running.
    Busy(String),
    /// `java.util.concurrent.CancellationException`.
    Cancelled(String),
    /// `java.lang.RuntimeException`: inference failure or a contained panic.
    Internal(String),
}

impl ApiError {
    fn class(&self) -> &'static str {
        match self {
            ApiError::Io(_) => "java/io/IOException",
            ApiError::InvalidArgument(_) => "java/lang/IllegalArgumentException",
            ApiError::State(_) => "java/lang/IllegalStateException",
            ApiError::Busy(_) => "java/util/concurrent/RejectedExecutionException",
            ApiError::Cancelled(_) => "java/util/concurrent/CancellationException",
            ApiError::Internal(_) => "java/lang/RuntimeException",
        }
    }

    fn message(&self) -> &str {
        match self {
            ApiError::Io(m)
            | ApiError::InvalidArgument(m)
            | ApiError::State(m)
            | ApiError::Busy(m)
            | ApiError::Cancelled(m)
            | ApiError::Internal(m) => m,
        }
    }
}

impl From<jni::errors::Error> for ApiError {
    fn from(error: jni::errors::Error) -> Self {
        ApiError::Internal(format!("jni: {error}"))
    }
}

impl From<EngineError> for ApiError {
    fn from(error: EngineError) -> Self {
        let text = error.to_string();
        match error {
            EngineError::ModelMissing(_) | EngineError::ModelRejected(_) => ApiError::Io(text),
            EngineError::InvalidAudio(_) => ApiError::InvalidArgument(text),
            EngineError::Cancelled => ApiError::Cancelled(text),
            EngineError::Inference(_) => ApiError::Internal(text),
        }
    }
}

/// Runs `body`, converting any error or panic into a thrown Java exception.
fn guard<T>(
    env: &mut JNIEnv,
    fallback: T,
    body: impl FnOnce(&mut JNIEnv) -> Result<T, ApiError>,
) -> T {
    let error = match catch_unwind(AssertUnwindSafe(|| body(env))) {
        Ok(Ok(value)) => return value,
        Ok(Err(error)) => error,
        Err(_) => ApiError::Internal("native engine panicked".to_string()),
    };
    // If an exception is already pending, the original one wins.
    let _ = env.throw_new(error.class(), error.message());
    fallback
}

fn init_logging() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("OfflineStt"),
        );
    });
}

fn lookup(id: jlong) -> Result<Arc<Handle>, ApiError> {
    let handles = registry().lock().map_err(|_| ApiError::State("engine registry poisoned".into()))?;
    handles
        .get(&id)
        .cloned()
        .ok_or_else(|| ApiError::State("the engine is closed".to_string()))
}

#[no_mangle]
pub extern "system" fn Java_app_roadstr_offlinestt_OfflineSttNative_load<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    model_path: JString<'l>,
    language: JString<'l>,
    threads: jint,
) -> jlong {
    guard(&mut env, 0, |env| {
        init_logging();
        let path: String = env.get_string(&model_path)?.into();
        let language: Option<String> = if language.is_null() {
            None
        } else {
            Some(env.get_string(&language)?.into())
        };
        if threads < 0 {
            return Err(ApiError::InvalidArgument("threads must not be negative".into()));
        }
        let cancel = CancelToken::new();
        let engine = SttEngine::load(&PathBuf::from(path), language, threads as usize, &cancel)?;
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        let handle = Arc::new(Handle { engine: Mutex::new(engine), cancel });
        registry()
            .lock()
            .map_err(|_| ApiError::State("engine registry poisoned".into()))?
            .insert(id, handle);
        Ok(id)
    })
}

/// Returns `[text, detectedLanguage ("" when unknown), audioMs, decodeMs]`.
#[no_mangle]
pub extern "system" fn Java_app_roadstr_offlinestt_OfflineSttNative_transcribe<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    id: jlong,
    pcm: JFloatArray<'l>,
    length: jint,
) -> jobjectArray {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let handle = lookup(id)?;
        let capacity = env.get_array_length(&pcm)?;
        if length < 0 || length > capacity {
            return Err(ApiError::InvalidArgument("length is outside the array".into()));
        }
        let mut samples = vec![0f32; length as usize];
        env.get_float_array_region(&pcm, 0, &mut samples)?;
        let result = {
            let mut engine = match handle.engine.try_lock() {
                Ok(guard) => guard,
                Err(TryLockError::WouldBlock) => {
                    return Err(ApiError::Busy("another transcription is running".into()))
                }
                Err(TryLockError::Poisoned(_)) => {
                    return Err(ApiError::State("the engine failed earlier and must be reloaded".into()))
                }
            };
            // A cancel() that raced ahead of this call must not abort the next one.
            handle.cancel.reset();
            engine.transcribe(&samples)?
        };
        let fields = [
            result.text,
            result.language.unwrap_or_default(),
            result.audio_ms.to_string(),
            result.decode_ms.to_string(),
        ];
        let array: JObjectArray = env.new_object_array(fields.len() as i32, "java/lang/String", JObject::null())?;
        for (index, field) in fields.iter().enumerate() {
            let text = env.new_string(field)?;
            env.set_object_array_element(&array, index as i32, &text)?;
        }
        Ok(array.into_raw())
    })
}

/// Aborts the call in flight (a no-op when idle). Never blocks.
#[no_mangle]
pub extern "system" fn Java_app_roadstr_offlinestt_OfflineSttNative_cancel<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    id: jlong,
) {
    guard(&mut env, (), |_| {
        lookup(id)?.cancel.cancel();
        Ok(())
    })
}

/// Frees the engine once any call still in flight returns. Idempotent.
#[no_mangle]
pub extern "system" fn Java_app_roadstr_offlinestt_OfflineSttNative_release<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    id: jlong,
) {
    guard(&mut env, (), |_| {
        let removed = registry()
            .lock()
            .map_err(|_| ApiError::State("engine registry poisoned".into()))?
            .remove(&id);
        if let Some(handle) = removed {
            handle.cancel.cancel();
        }
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_app_roadstr_offlinestt_OfflineSttNative_version<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let text = format!("offlinestt {} (transcribe.cpp 0.2.3)", env!("CARGO_PKG_VERSION"));
        Ok(env.new_string(text)?.into_raw())
    })
}
