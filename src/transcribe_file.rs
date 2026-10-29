//! Bounded file decoding: one model window plus a ten-second Java input block.
//! Complete-job dictionary cleanup and overlapping boundary reconciliation match
//! ordinary dictation. Each `FileTranscription` owns one handle, its own
//! cancellation token and exact job, so the share screen, the activity-result
//! contract and the headless recognition-service request never share state.
//! File jobs open no microphone and are never cut short by interactive caps:
//! they end at decoder EOS, on failure, or on cancellation.
use crate::engine::{self, Engine, TranscriptionJob};
use jni::objects::{GlobalRef, JClass, JFloatArray, JObject};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex};

/// Largest block `AudioFileDecoder` hands over (10 s at 16 kHz).
const MAX_BLOCK_SAMPLES: jint = 160_000;

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
    partials: bool,
}
static STATES: Lazy<Mutex<HashMap<i64, State>>> = Lazy::new(|| Mutex::new(HashMap::new()));
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

fn notify(env: &mut JNIEnv, target: &JObject, method: &str, text: &str) {
    if let Ok(text) = env.new_string(text) {
        let _ = env.call_method(target, method, "(Ljava/lang/String;)V", &[(&text).into()]);
    }
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
}

/// Creates one job and warms the shared model on a worker thread. The owner
/// receives `onStatusUpdate("Ready")` (or an `Error:` status) when it may push.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_FileTranscription_nativeCreate(
    env: JNIEnv,
    _: JClass,
    owner: JObject,
    partials: jboolean,
) -> jlong {
    let Ok(vm) = env.get_java_vm() else {
        return 0;
    };
    let Ok(target) = env.new_global_ref(owner) else {
        return 0;
    };
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::SeqCst);
    let cancelled = Arc::new(AtomicBool::new(false));
    let session = Arc::new(Mutex::new(FileSession {
        buffer: crate::file_audio::Buffer::default(),
        assembled: String::new(),
        engine: None,
        job: None,
        failed: false,
        finished: false,
    }));
    let mut states = STATES.lock().unwrap_or_else(|p| p.into_inner());
    if states.len() >= 2 { return 0; }
    states.insert(
        handle,
        State {
            target: target.clone(),
            session,
            cancelled: cancelled.clone(),
            partials: partials != JNI_FALSE,
        },
    );
    drop(states);
    std::thread::spawn(move || {
        if !cancelled.load(Ordering::SeqCst) {
            let _ = engine::ensure_loaded_from_thread(&Arc::new(vm), &target);
        }
    });
    handle
}

/// Cancels and forgets one job. Idempotent; a window already inside the model
/// finishes behind the engine mutex, but nothing is delivered for it.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_FileTranscription_nativeRelease(
    _env: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    if let Some(old) = STATES.lock().unwrap_or_else(|p| p.into_inner()).remove(&handle) {
        old.cancelled.store(true, Ordering::SeqCst);
    }
}

type Handles = (Arc<Mutex<FileSession>>, Arc<AtomicBool>, GlobalRef, bool);
fn handles(handle: jlong) -> Option<Handles> {
    STATES
        .lock()
        .unwrap()
        .get(&handle)
        .map(|s| (s.session.clone(), s.cancelled.clone(), s.target.clone(), s.partials))
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
fn piece(s: &mut FileSession, audio: &[f32], intermediate: bool) -> Result<(), String> {
    let _permit = engine::INFERENCE_GATE.acquire(false);
    let engine = s.engine.as_ref().unwrap().clone();
    let FileSession { job, assembled, .. } = s;
    // A native panic must end this job as a failure, not as a silent hang.
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        engine
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .decode_window(job.as_mut().unwrap(), audio, intermediate, assembled)
    }))
    .unwrap_or_else(|_| Err("file window failed unexpectedly".into()))
}
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_FileTranscription_nativePush(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    samples: JFloatArray,
    length: jint,
) -> jboolean {
    let Some((session, cancelled, target, partials)) = handles(handle) else {
        return JNI_FALSE;
    };
    if cancelled.load(Ordering::SeqCst)
        || length < 0
        || length > MAX_BLOCK_SAMPLES
        || env.get_array_length(&samples).unwrap_or(0) < length
    {
        return JNI_FALSE;
    }
    let mut chunk = vec![0.; length as usize];
    if env.get_float_array_region(&samples, 0, &mut chunk).is_err() {
        return JNI_FALSE;
    }
    let mut s = session.lock().unwrap_or_else(|p| p.into_inner());
    let result = (|| -> Result<(), String> {
        let limit = ensure_job(&mut s)?;
        s.buffer.push(&chunk);
        while let Some(audio) = s.buffer.take_ready(limit) {
            if cancelled.load(Ordering::SeqCst) {
                return Ok(());
            }
            piece(&mut s, &audio, true)?;
            if partials && !cancelled.load(Ordering::SeqCst) {
                let text =
                    Engine::finish_file_job(s.job.as_ref().unwrap().clone(), s.assembled.clone());
                notify(&mut env, target.as_obj(), "onPartialText", &text);
            }
        }
        Ok(())
    })();
    if result.is_err() && !cancelled.load(Ordering::SeqCst) {
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
pub unsafe extern "system" fn Java_dev_notune_transcribe_FileTranscription_nativeFinish(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    let Some((session, cancelled, target, _)) = handles(handle) else {
        return;
    };
    let mut s = session.lock().unwrap_or_else(|p| p.into_inner());
    if s.failed || s.finished || cancelled.load(Ordering::SeqCst) {
        return;
    }
    let result = (|| -> Result<String, String> {
        ensure_job(&mut s)?;
        let tail = s.buffer.finish();
        if !tail.is_empty() {
            piece(&mut s, &tail, false)?;
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
    if result.is_err() { s.failed = true; }
    drop(s); // Java result handling may write to a caller-owned output provider.
    match result {
        Ok(text) => notify(&mut env, target.as_obj(), "onTextTranscribed", &text),
        Err(_) => {
            notify(
                &mut env,
                target.as_obj(),
                "onStatusUpdate",
                "Error: file transcription failed",
            );
        }
    }
}
