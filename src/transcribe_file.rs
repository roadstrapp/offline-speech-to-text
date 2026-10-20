//! Bounded file decoding: one model window plus a ten-second Java input block.
//! Complete-job dictionary cleanup and overlapping boundary reconciliation match
//! ordinary dictation. Each activity owns its cancellation token and exact job.
use crate::engine::{self, Engine, TranscriptionJob};
use jni::objects::{GlobalRef, JClass, JFloatArray, JObject};
use jni::sys::{jboolean, jint, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

struct FileSession {
    buffer: crate::file_audio::Buffer,
    assembled: String,
    engine: Option<Arc<Mutex<Engine>>>,
    job: Option<TranscriptionJob>,
    failed: bool,
    finished: bool,
}
struct State {
    target: GlobalRef,
    session: Arc<Mutex<FileSession>>,
    cancelled: Arc<AtomicBool>,
}
static STATE: Lazy<Mutex<Option<State>>> = Lazy::new(|| Mutex::new(None));
fn notify(env: &mut JNIEnv, target: &JObject, method: &str, text: &str) {
    if let Ok(text) = env.new_string(text) {
        let _ = env.call_method(target, method, "(Ljava/lang/String;)V", &[(&text).into()]);
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_initNative(
    env: JNIEnv,
    _: JClass,
    activity: JObject,
) {
    let Ok(vm) = env.get_java_vm() else {
        return;
    };
    let Ok(target) = env.new_global_ref(activity) else {
        return;
    };
    let cancelled = Arc::new(AtomicBool::new(false));
    let session = Arc::new(Mutex::new(FileSession {
        buffer: crate::file_audio::Buffer::default(),
        assembled: String::new(),
        engine: None,
        job: None,
        failed: false,
        finished: false,
    }));
    if let Some(old) = STATE.lock().unwrap().replace(State {
        target: target.clone(),
        session,
        cancelled: cancelled.clone(),
    }) {
        old.cancelled.store(true, Ordering::SeqCst);
    }
    std::thread::spawn(move || {
        if !cancelled.load(Ordering::SeqCst) {
            let _ = engine::ensure_loaded_from_thread(&Arc::new(vm), &target);
        }
    });
}
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_cleanupNative(
    env: JNIEnv,
    _: JClass,
    activity: JObject,
) {
    let mut guard = STATE.lock().unwrap();
    if guard
        .as_ref()
        .is_some_and(|s| env.is_same_object(&s.target, &activity).unwrap_or(false))
    {
        if let Some(old) = guard.take() {
            old.cancelled.store(true, Ordering::SeqCst);
        }
    }
}
fn handles(
    env: &JNIEnv,
    activity: &JObject,
) -> Option<(Arc<Mutex<FileSession>>, Arc<AtomicBool>, GlobalRef)> {
    STATE
        .lock()
        .unwrap()
        .as_ref()
        .filter(|s| env.is_same_object(&s.target, activity).unwrap_or(false))
        .map(|s| (s.session.clone(), s.cancelled.clone(), s.target.clone()))
}
fn ensure_job(s: &mut FileSession) -> Result<usize, String> {
    if s.failed || s.finished {
        return Err("file transcription stopped".into());
    }
    if s.engine.is_none() {
        let engine = engine::get_engine().ok_or("model not ready")?;
        let locked = engine.lock().map_err(|_| "model unavailable")?;
        s.job = Some(locked.begin_transcription());
        drop(locked);
        s.engine = Some(engine);
    }
    Ok(s.engine
        .as_ref()
        .unwrap()
        .lock()
        .map_err(|_| "model unavailable")?
        .file_window_limit())
}
fn piece(s: &mut FileSession, audio: &[f32]) -> Result<(), String> {
    let _permit = engine::INFERENCE_GATE.acquire(false);
    s.engine
        .as_ref()
        .unwrap()
        .lock()
        .map_err(|_| "model unavailable")?
        .decode_window(
            s.job.as_mut().unwrap(),
            audio,
            true,
            &mut s.assembled,
        )
}
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_pushSamples(
    mut env: JNIEnv,
    _: JClass,
    activity: JObject,
    samples: JFloatArray,
    length: jint,
) -> jboolean {
    let Some((session, cancelled, target)) = handles(&env, &activity) else {
        return JNI_FALSE;
    };
    if cancelled.load(Ordering::SeqCst)
        || length < 0
        || length > 160_000
        || env.get_array_length(&samples).unwrap_or(0) < length
    {
        return JNI_FALSE;
    }
    let mut chunk = vec![0.; length as usize];
    if env.get_float_array_region(&samples, 0, &mut chunk).is_err() {
        return JNI_FALSE;
    }
    let mut s = session.lock().unwrap();
    let result = (|| -> Result<(), String> {
        let limit = ensure_job(&mut s)?;
        s.buffer.push(&chunk);
        while let Some(audio) = s.buffer.take_ready(limit) {
            if cancelled.load(Ordering::SeqCst) {
                return Ok(());
            }
            piece(&mut s, &audio)?;
            if !cancelled.load(Ordering::SeqCst) {
                let text =
                    Engine::finish_file_job(s.job.as_ref().unwrap().clone(), s.assembled.clone());
                notify(&mut env, target.as_obj(), "onPartialText", &text);
            }
        }
        Ok(())
    })();
    if result.is_err() {
        s.failed = true;
        notify(
            &mut env,
            target.as_obj(),
            "onStatusUpdate",
            "Error: file transcription failed",
        );
    }
    if s.failed || s.finished || cancelled.load(Ordering::SeqCst) {
        JNI_FALSE
    } else {
        JNI_TRUE
    }
}
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_finishSamples(
    mut env: JNIEnv,
    _: JClass,
    activity: JObject,
) {
    let Some((session, cancelled, target)) = handles(&env, &activity) else {
        return;
    };
    let mut s = session.lock().unwrap();
    if s.failed || s.finished || cancelled.load(Ordering::SeqCst) {
        return;
    }
    let result = (|| -> Result<String, String> {
        ensure_job(&mut s)?;
        let tail = s.buffer.finish();
        if !tail.is_empty() {
            piece(&mut s, &tail)?;
        }
        Ok(Engine::finish_file_job(
            s.job.take().unwrap(),
            std::mem::take(&mut s.assembled),
        ))
    })();
    if cancelled.load(Ordering::SeqCst) {
        return;
    }
    s.finished = true;
    match result {
        Ok(text) => notify(&mut env, target.as_obj(), "onTextTranscribed", &text),
        Err(_) => {
            s.failed = true;
            notify(
                &mut env,
                target.as_obj(),
                "onStatusUpdate",
                "Error: file transcription failed",
            );
        }
    }
}
