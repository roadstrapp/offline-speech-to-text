use crate::engine;
use jni::objects::{JClass, JObject};
use jni::JNIEnv;
use std::sync::Arc;

/// Reports a finished benchmark run back to `MainActivity.onBenchmarkResult`.
/// `error` is empty on success.
fn deliver_benchmark_result(
    env: &mut JNIEnv,
    activity: &JObject,
    audio_secs: f32,
    compute_secs: f32,
    error: &str,
) {
    if let Ok(jerr) = env.new_string(error) {
        let _ = env.call_method(
            activity,
            "onBenchmarkResult",
            "(FFLjava/lang/String;)V",
            &[audio_secs.into(), compute_secs.into(), (&jerr).into()],
        );
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_MainActivity_initNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Off),
    );

    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let activity_ref = env
        .new_global_ref(&activity)
        .expect("Failed to ref activity");

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_arc, &activity_ref);
    });
}

/// Benchmark: transcribes the given samples once on a worker thread and calls
/// back with audio seconds vs compute seconds. Engine loading (if it is still
/// in progress) is waited for but not counted into the measured time.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_MainActivity_benchmarkNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
    samples: jni::objects::JFloatArray,
    length: jni::sys::jint,
) {
    let len = length as usize;
    let mut buffer = vec![0.0f32; len];
    if len == 0
        || env
            .get_float_array_region(&samples, 0, &mut buffer)
            .is_err()
    {
        return;
    }

    let vm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let activity_ref = match env.new_global_ref(&activity) {
        Ok(r) => r,
        Err(_) => return,
    };

    std::thread::spawn(move || {
        let mut env = match vm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return,
        };
        let audio_secs = buffer.len() as f32 / 16_000.0;

        if let Err(e) = engine::ensure_loaded_from_thread(&vm, &activity_ref) {
            deliver_benchmark_result(&mut env, &activity_ref.as_obj(), audio_secs, 0.0, &e);
            return;
        }
        let engine_arc = match engine::get_engine() {
            Some(e) => e,
            None => {
                deliver_benchmark_result(
                    &mut env,
                    &activity_ref.as_obj(),
                    audio_secs,
                    0.0,
                    "model not loaded",
                );
                return;
            }
        };

        let started = std::time::Instant::now();
        let result = engine::transcribe_shared(&engine_arc, buffer);
        let compute_secs = started.elapsed().as_secs_f32();

        match result {
            Ok(_text) => {
                log::info!(
                    "benchmark: {:.1}s audio in {:.2}s",
                    audio_secs,
                    compute_secs
                );
                deliver_benchmark_result(
                    &mut env,
                    &activity_ref.as_obj(),
                    audio_secs,
                    compute_secs,
                    "",
                );
            }
            Err(e) => {
                deliver_benchmark_result(&mut env, &activity_ref.as_obj(), audio_secs, 0.0, &e);
            }
        }
    });
}

/// Same-process, same-signature instrumentation seam. It cannot open a file,
/// publish text, change settings or acquire the microphone. Only fixed numeric
/// equality/timing evidence escapes; the two hypotheses are dropped locally.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_MainActivity_verifyPreparedWindowsNative(
    mut env: JNIEnv,
    activity: JObject,
    samples: jni::objects::JFloatArray,
) -> jni::sys::jlongArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(
        || -> Result<Vec<i64>, String> {
            let len = env.get_array_length(&samples).map_err(|_| "array")? as usize;
            if len == 0 || len > 90 * 16_000 {
                return Err("bounded fixture required".into());
            }
            let mut audio = vec![0.0; len];
            env.get_float_array_region(&samples, 0, &mut audio)
                .map_err(|_| "array")?;
            engine::ensure_loaded(&mut env, &activity)?;
            let eng = engine::get_engine().ok_or("model")?;
            let mut guard = eng.lock().unwrap_or_else(|p| p.into_inner());
            let limit = guard
                .background_window_limit()
                .ok_or("bounded model required")?;
            let mut final_audio = audio.clone();
            crate::audio_finalization::append_final_decode_silence(&mut final_audio);
            let windows = crate::long_audio::windows(&final_audio, limit);
            if windows.len() < 2 {
                return Err("long fixture required".into());
            }
            let mut deferred = guard.begin_transcription();
            let started = std::time::Instant::now();
            guard.transcribe_into(&mut deferred, &final_audio)?;
            let expected = deferred.finish();
            let deferred_ms = started.elapsed().as_millis() as i64;
            let mut early = guard.begin_transcription();
            let started = std::time::Instant::now();
            let mut start = 0;
            while let Some(window) = crate::long_audio::complete_window(&audio, start, limit) {
                start = window.end - crate::long_audio::overlap_samples(limit);
                guard.prepare_complete_window(&mut early, audio[window.clone()].to_vec())?;
            }
            let prepared = engine::Engine::prepared_window_count(&early) as i64;
            if prepared < 1 || prepared as usize >= windows.len() {
                return Err("fixture did not reuse a strict prepared prefix".into());
            }
            let prepare_ms = started.elapsed().as_millis() as i64;
            let started = std::time::Instant::now();
            guard.transcribe_into(&mut early, &final_audio)?;
            let actual = early.finish();
            let stop_ms = started.elapsed().as_millis() as i64;
            Ok(vec![
                i64::from(!expected.trim().is_empty() && expected == actual),
                prepared,
                deferred_ms,
                prepare_ms,
                stop_ms,
            ])
        },
    ));
    match result {
        Ok(Ok(values)) => env
            .new_long_array(values.len() as i32)
            .ok()
            .and_then(|array| {
                env.set_long_array_region(&array, 0, &values)
                    .ok()
                    .map(|()| array.into_raw())
            })
            .unwrap_or(std::ptr::null_mut()),
        _ => std::ptr::null_mut(),
    }
}
