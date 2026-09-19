//! Loads and serves the speech-to-text engine (transcribe.cpp, GGUF models).
//!
//! The engine is a process-wide singleton behind `Arc<Mutex<..>>`: a
//! transcribe.cpp session may only be used by one thread at a time, which the
//! mutex guarantees. Model selection is read from marker files in filesDir
//! (written by `ModelsActivity`): an imported GGUF if one is selected, the
//! bundled model otherwise — with the bundled model as fallback if the
//! imported one fails to load.

use once_cell::sync::Lazy;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Condvar, Mutex};

use jni::objects::{GlobalRef, JObject};
use jni::JNIEnv;

use crate::assets;

/// File in filesDir naming the imported GGUF (a file under `models/`) to use
/// instead of the bundled model. Absent or empty = bundled model.
const ACTIVE_MODEL_FILE: &str = "active_model";
/// File in filesDir with an optional language hint — a locale like `en-US`
/// or `auto`. Absent or empty = let the model autodetect.
const MODEL_LANGUAGE_FILE: &str = "model_language";
/// Marker file in filesDir: when present, models that support translation
/// (e.g. Whisper) translate speech to English instead of transcribing it.
/// Ignored by models without translation support.
const MODEL_TRANSLATE_FILE: &str = "model_translate";
/// Optional file in filesDir with the CPU thread count for inference.
/// Absent/invalid/0 = default (all cores).
const MODEL_THREADS_FILE: &str = "model_threads";

/// A loaded transcribe.cpp session plus the options applied to every run.
pub struct Engine {
    session: transcribe_cpp::Session,
    language: Option<String>,
    task: transcribe_cpp::Task,
    /// Family-specific decode options attached to every run; `None` for
    /// models that don't take the whisper run extension.
    run_ext: Option<transcribe_cpp::RunExtension>,
    supports_initial_prompt: bool,
    custom_terms_path: PathBuf,
    files_dir: PathBuf,
    chunk_plan: crate::long_audio_chunking::Plan,
    /// Status reported once loading succeeded; carries a warning when the
    /// translate setting can't do what the user expects with this model.
    ready_status: &'static str,
}

impl Engine {
    fn load(
        model_path: &Path,
        language: Option<String>,
        translate: bool,
        threads: i32,
        files_dir: PathBuf,
    ) -> Result<Engine, String> {
        if !model_path.is_file() {
            return Err(format!("model file not found: {}", model_path.display()));
        }
        let model = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            transcribe_cpp::Model::load(model_path)
        }))
        .map_err(|_| "model loader rejected this file".to_string())?
        .map_err(|e| e.to_string())?;
        // Translation is gated on the model's capabilities: models without it
        // (e.g. Parakeet) silently keep transcribing, so the setting can stay
        // on while switching models.
        let task = if translate && model.capabilities().supports_translate {
            transcribe_cpp::Task::Translate
        } else {
            if translate {
                log::info!("translate requested but unsupported by this model; transcribing");
            }
            transcribe_cpp::Task::Transcribe
        };
        // Silently doing something other than what the translate switch says
        // looks like a bug (still-untranslated subtitles), so say it in the
        // status. Whisper Turbo is special-cased: it advertises translation
        // but was distilled without translation data — verified on-device to
        // keep transcribing German as German with task=Translate.
        let ready_status = if translate && task == transcribe_cpp::Task::Transcribe {
            "Ready (this model can't translate)"
        } else if translate && model.variant().contains("turbo") {
            "Ready (note: Whisper Turbo translates poorly; use another Whisper)"
        } else {
            "Ready"
        };
        // Whisper's stock recipe re-decodes a chunk at up to five higher
        // temperatures when its quality gates fail, so one noisy chunk can
        // cost several full decodes. For an interactive app a single greedy
        // pass is the better trade: worst case is a worse line of text, not
        // a multiplied wait. temperature_inc = 0 turns the retry ladder off;
        // models that don't take the whisper run extension are unaffected.
        let is_whisper = model.accepts_ext(
            transcribe_cpp::ExtSlot::Run,
            transcribe_cpp::sys::TRANSCRIBE_EXT_KIND_WHISPER_RUN,
        );
        let supports_initial_prompt =
            is_whisper && model.supports(transcribe_cpp::Feature::InitialPrompt);
        let run_ext = if is_whisper {
            Some(transcribe_cpp::RunExtension::Whisper(
                transcribe_cpp::WhisperRunOptions {
                    temperature_inc: Some(0.0),
                    ..Default::default()
                },
            ))
        } else {
            None
        };

        log::info!(
            "engine: {} threads, task {:?}, single-pass decode: {}",
            threads,
            task,
            run_ext.is_some()
        );
        let options = transcribe_cpp::SessionOptions {
            n_threads: threads,
            ..Default::default()
        };
        let session = model.session_with(&options).map_err(|e| e.to_string())?;
        Ok(Engine {
            session,
            language,
            task,
            run_ext,
            supports_initial_prompt,
            custom_terms_path: files_dir.join(crate::custom_terms::FILE_NAME),
            files_dir,
            chunk_plan: if is_whisper {
                crate::long_audio_chunking::Plan::for_whisper()
            } else {
                crate::long_audio_chunking::Plan::for_conformer()
            },
            ready_status,
        })
    }

    /// Transcribes 16 kHz mono f32 samples to text. Long input is transcribed
    /// in short quiet-point chunks so full-attention models remain bounded.
    pub fn transcribe(&mut self, samples: Vec<f32>) -> Result<String, String> {
        // Snapshot once per complete transcription. Editing the list while a
        // long recording is being decoded therefore cannot change only the
        // later chunks. Unsupported models never read or receive the prompt.
        let prompt = if self.supports_initial_prompt {
            crate::custom_terms::load_prompt(&self.custom_terms_path)
        } else {
            None
        };
        // Exact personal corrections are model-independent, including for
        // Parakeet. Snapshot them beside the Whisper prompt so one long
        // transcription cannot mix old and new settings across chunks.
        let corrections = crate::personal_corrections::load(&self.files_dir);
        let run_ext = self.run_extension(prompt);
        let text = if samples.len() <= self.chunk_plan.max_run_samples() {
            self.run(&samples, &run_ext)?
        } else {
            let mut text = String::new();
            let mut rest: &[f32] = &samples;
            while !rest.is_empty() {
                let take = self.chunk_plan.next_chunk_len(rest);
                let piece = self.run(&rest[..take], &run_ext)?;
                let piece = piece.trim();
                if !piece.is_empty() {
                    if !text.is_empty() {
                        text.push(' ');
                    }
                    text.push_str(piece);
                }
                rest = &rest[take..];
            }
            text
        };
        if let Some(rules) = corrections.as_deref() {
            Ok(crate::personal_corrections::apply(&text, rules))
        } else {
            Ok(text)
        }
    }

    fn run_extension(&self, prompt: Option<String>) -> Option<transcribe_cpp::RunExtension> {
        self.run_ext.clone().map(|extension| match extension {
            transcribe_cpp::RunExtension::Whisper(mut options) => {
                if self.supports_initial_prompt {
                    options.initial_prompt = prompt;
                }
                transcribe_cpp::RunExtension::Whisper(options)
            }
            other => other,
        })
    }

    /// One model run. A rejected language hint is degraded instead of
    /// failing the transcription: `de-DE` retries as `de`, then as no hint
    /// (each model knows a different set of tags — e.g. Parakeet v3 takes
    /// locales/short codes, English-only models take none). The degraded
    /// value is kept so later runs skip the rejected attempts.
    fn run(
        &mut self,
        samples: &[f32],
        run_ext: &Option<transcribe_cpp::RunExtension>,
    ) -> Result<String, String> {
        loop {
            let opts = transcribe_cpp::RunOptions {
                language: self.language.clone(),
                task: self.task,
                family: run_ext.clone(),
                ..Default::default()
            };
            match self.session.run(samples, &opts) {
                Ok(t) => return Ok(t.text),
                Err(transcribe_cpp::Error::Unsupported(msg)) if self.language.is_some() => {
                    let lang = self.language.take().unwrap();
                    self.language = lang.split_once('-').map(|(primary, _)| primary.to_string());
                    log::warn!(
                        "language hint '{}' rejected ({}); retrying with {:?}",
                        lang,
                        msg,
                        self.language
                    );
                }
                Err(e) => return Err(e.to_string()),
            }
        }
    }
}

/// Holds the loaded engine singleton.
static GLOBAL_ENGINE: Lazy<Mutex<Option<Arc<Mutex<Engine>>>>> = Lazy::new(|| Mutex::new(None));

/// Loading coordination state + condvar for waiters.
static LOAD_STATE: Lazy<(Mutex<LoadState>, Condvar)> =
    Lazy::new(|| (Mutex::new(LoadState::Idle), Condvar::new()));

#[derive(Debug, Clone, PartialEq)]
enum LoadState {
    /// No load in progress
    Idle,
    /// A thread is currently loading the model
    Loading,
    /// Loading completed successfully
    Done,
    /// Loading failed
    Failed(String),
}

pub fn get_engine() -> Option<Arc<Mutex<Engine>>> {
    GLOBAL_ENGINE.lock().unwrap().clone()
}

/// Runs a transcription on the shared engine. Two layers of hardening keep a
/// single bad run from freezing every later one (the symptom would be an IME
/// stuck at "Processing" until its process dies, since notify callbacks stop
/// coming): a panic anywhere in the engine stack is caught and surfaced as a
/// normal error, and a lock poisoned by an earlier panic is recovered instead
/// of propagating the poison forever.
pub fn transcribe_shared(engine: &Arc<Mutex<Engine>>, samples: Vec<f32>) -> Result<String, String> {
    let audio_secs = samples.len() as f64 / 16_000.0;
    let started = std::time::Instant::now();
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut guard = engine
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        guard.transcribe(samples)
    }))
    .unwrap_or_else(|_| {
        log::error!("transcription panicked; reporting as error");
        Err("transcription failed unexpectedly, please try again".to_string())
    });
    log::info!(
        "transcribed {:.1}s audio in {:.2}s",
        audio_secs,
        started.elapsed().as_secs_f64()
    );
    result
}

pub fn is_engine_loaded() -> bool {
    GLOBAL_ENGINE.lock().unwrap().is_some()
}

/// Drops the loaded engine and clears the load state so the next
/// `ensure_loaded*` call reloads with the current model selection. Waits for
/// an in-flight load to finish first, so a reload can't race a load of the
/// previous selection. Call from a background thread.
pub fn reset() {
    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap();
    while *state == LoadState::Loading {
        state = cvar.wait(state).unwrap();
    }
    *GLOBAL_ENGINE.lock().unwrap() = None;
    *state = LoadState::Idle;
}

fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;)V",
            &[(&jmsg).into()],
        );
    }
}

/// Ensures the engine is loaded. Safe to call from multiple threads
/// concurrently; reports status via the target's `onStatusUpdate` callback.
///
/// Convenience wrapper around [`ensure_loaded_from_thread`] for JNI entry
/// points that already hold an attached `JNIEnv`.
pub fn ensure_loaded(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    let jvm = Arc::new(env.get_java_vm().map_err(|e| e.to_string())?);
    let context_ref = env.new_global_ref(context).map_err(|e| e.to_string())?;
    ensure_loaded_from_thread(&jvm, &context_ref)
}

/// Ensures the engine is loaded. Safe to call from multiple threads concurrently.
///
/// - If already loaded, returns immediately.
/// - If another thread is loading, waits for it to finish.
/// - If no one is loading, this thread takes ownership of loading.
/// - If a previous load failed, retries.
///
/// Reports status via the target's `onStatusUpdate` JNI callback.
pub fn ensure_loaded_from_thread(
    jvm: &Arc<jni::JavaVM>,
    target_ref: &GlobalRef,
) -> Result<(), String> {
    let notify = |msg: &str| {
        if let Ok(mut env) = jvm.attach_current_thread() {
            notify_status(&mut env, target_ref.as_obj(), msg);
        }
    };

    // Fast path: already loaded
    if is_engine_loaded() {
        notify("Ready");
        return Ok(());
    }

    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap();

    // Re-check under lock
    if is_engine_loaded() {
        notify("Ready");
        return Ok(());
    }

    match &*state {
        LoadState::Loading => {
            // Another thread is loading — wait for it
            notify("Waiting for model...");
            while *state == LoadState::Loading {
                state = cvar.wait(state).unwrap();
            }
            drop(state);

            if is_engine_loaded() {
                notify("Ready");
                Ok(())
            } else {
                let msg = "Model failed to load".to_string();
                notify(&format!("Error: {}", msg));
                Err(msg)
            }
        }
        LoadState::Done => {
            notify("Ready");
            Ok(())
        }
        LoadState::Idle | LoadState::Failed(_) => {
            // We take ownership of loading (retry on previous failure)
            *state = LoadState::Loading;
            drop(state);

            let result = if let Ok(mut env) = jvm.attach_current_thread() {
                do_load(&mut env, target_ref.as_obj())
            } else {
                Err("Failed to attach JNI thread".to_string())
            };

            let mut state = lock.lock().unwrap();
            match &result {
                Ok(()) => *state = LoadState::Done,
                Err(msg) => *state = LoadState::Failed(msg.clone()),
            }
            cvar.notify_all();
            result
        }
    }
}

/// Inference thread count: the number of performance cores, capped at 4.
///
/// Phone SoCs are heterogeneous: fast cores paired with slow efficiency
/// cores. ggml synchronizes all threads after each operation, so a thread
/// on a slow core stalls the whole pool, and using every core is slower
/// than using only the fast ones. Cores are classified by their maximum
/// frequency from sysfs: within 70% of the fastest core counts as fast.
/// The count is capped at 4 because grabbing every fast core leaves none
/// for the rest of the system (audio pipeline, the app playing the sound),
/// and any preempted worker stalls the pool at the next op barrier; past
/// 4 threads the matmuls are memory-bound on phone-class SoCs anyway, and
/// more threads mainly build up heat. If sysfs is unreadable, falls back
/// to a conservative 4. The `model_threads` config file (user-settable in
/// the Models screen) overrides the heuristic.
fn performance_core_count() -> i32 {
    let mut freqs: Vec<u64> = Vec::new();
    for i in 0..64 {
        let path = format!("/sys/devices/system/cpu/cpu{i}/cpufreq/cpuinfo_max_freq");
        match std::fs::read_to_string(&path) {
            Ok(s) => match s.trim().parse::<u64>() {
                Ok(f) => freqs.push(f),
                Err(_) => break,
            },
            Err(_) => break,
        }
    }
    match freqs.iter().max() {
        Some(&max) if max > 0 => {
            let fast = freqs.iter().filter(|&&f| f * 10 >= max * 7).count();
            let threads = fast.clamp(1, 4);
            log::info!(
                "cpu clusters {:?} kHz -> {} performance cores -> {} threads",
                freqs,
                fast,
                threads
            );
            threads as i32
        }
        _ => std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(4)
            .min(4) as i32,
    }
}

/// Reads a single-value config file (trimmed); `None` if absent or empty.
fn read_config(path: &Path) -> Option<String> {
    let s = std::fs::read_to_string(path).ok()?;
    let s = s.trim();
    if s.is_empty() {
        None
    } else {
        Some(s.to_string())
    }
}

/// Performs the model load: the selected imported GGUF if any (falling back
/// to the bundled model on failure), otherwise the bundled model.
fn do_load(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    let files_dir = assets::files_dir(env, context).map_err(|e| {
        let msg = format!("Failed to resolve filesDir: {}", e);
        notify_status(env, context, &format!("Error: {}", msg));
        msg
    })?;
    // Absent/empty or "auto" = no hint (the model's automatic mode). A tag
    // the model doesn't know is degraded per run — see Engine::run.
    let language = read_config(&files_dir.join(MODEL_LANGUAGE_FILE))
        .filter(|l| !l.eq_ignore_ascii_case("auto"));
    let translate = files_dir.join(MODEL_TRANSLATE_FILE).exists();
    let threads = read_config(&files_dir.join(MODEL_THREADS_FILE))
        .and_then(|s| s.parse::<i32>().ok())
        .filter(|&n| n > 0)
        .unwrap_or_else(performance_core_count);

    if let Some(name) = read_config(&files_dir.join(ACTIVE_MODEL_FILE)) {
        let path = files_dir.join("models").join(&name);
        notify_status(env, context, &format!("Loading model {}...", name));
        match Engine::load(
            &path,
            language.clone(),
            translate,
            threads,
            files_dir.clone(),
        ) {
            Ok(engine) => {
                let status = engine.ready_status;
                *GLOBAL_ENGINE.lock().unwrap() = Some(Arc::new(Mutex::new(engine)));
                notify_status(env, context, status);
                return Ok(());
            }
            Err(e) => {
                log::error!("Imported model {} failed to load: {}", path.display(), e);
                notify_status(
                    env,
                    context,
                    &format!("Error loading {}: {} — using built-in model", name, e),
                );
                // fall through to the bundled model
            }
        }
    }

    notify_status(env, context, "Checking assets...");

    let path = assets::extract_builtin_model(env, context).map_err(|e| {
        let msg = format!("Asset error: {}", e);
        notify_status(env, context, &format!("Error: {}", msg));
        msg
    })?;

    notify_status(env, context, "Loading model...");

    match Engine::load(&path, language, translate, threads, files_dir) {
        Ok(engine) => {
            let status = engine.ready_status;
            *GLOBAL_ENGINE.lock().unwrap() = Some(Arc::new(Mutex::new(engine)));
            notify_status(env, context, status);
            Ok(())
        }
        Err(e) => {
            // Load failed — likely corrupt/incomplete extraction. Invalidate
            // it so the next attempt re-extracts from the APK.
            assets::invalidate_builtin_model(&path);
            let msg = format!("Model error: {}", e);
            notify_status(env, context, &format!("Error: {}", msg));
            Err(msg)
        }
    }
}
