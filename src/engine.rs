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
use std::sync::atomic::{AtomicI32, Ordering};
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
static ACTIVE_THREADS: AtomicI32 = AtomicI32::new(0);

/// Settings captured once for a complete user transcription.
pub(crate) struct TranscriptionJob {
    run_ext: Option<transcribe_cpp::RunExtension>,
    preferred_terms: Option<Vec<String>>,
    corrections: Option<Vec<crate::personal_corrections::Rule>>,
    text: String,
    english_output: bool,
}

impl TranscriptionJob {
    pub(crate) fn finish(self) -> String {
        let raw_text = if self.english_output {
            crate::english_fillers::remove_uh_owned(self.text)
        } else {
            self.text
        };
        // Explicit user corrections take precedence over a close-spelling
        // guess: apply them to the raw model result before preferred words.
        let (text, protected) = if let Some(rules) = self.corrections.as_deref() {
            crate::personal_corrections::apply_with_ranges(&raw_text, rules)
        } else {
            (raw_text, Vec::new())
        };
        if let Some(terms) = self.preferred_terms.as_deref() {
            crate::preferred_terms::apply_with_protected(&text, terms, &protected)
        } else {
            text
        }
    }
}

/// A loaded transcribe.cpp session plus the options applied to every run.
pub struct Engine {
    session: transcribe_cpp::Session,
    language: Option<String>,
    task: transcribe_cpp::Task,
    english_only_model: bool,
    translates_to_english: bool,
    /// Family-specific decode options attached to every run; `None` for
    /// models that don't take the whisper run extension.
    run_ext: Option<transcribe_cpp::RunExtension>,
    supports_initial_prompt: bool,
    custom_terms_path: PathBuf,
    files_dir: PathBuf,
    /// `None` means the runtime preserves long-input context itself (Whisper).
    /// Other offline families use overlapping bounded windows so attention
    /// memory cannot grow with an arbitrarily long recording.
    bounded_window_samples: Option<usize>,
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
        let capabilities = model.capabilities();
        let task = if translate && capabilities.supports_translate {
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
        let english_only_model = capabilities.languages.len() == 1
            && crate::english_fillers::is_english_tag(&capabilities.languages[0]);
        // Whisper Turbo advertises translation despite being unreliable at
        // producing English output; don't apply English-only cleanup there.
        let translates_to_english =
            task == transcribe_cpp::Task::Translate && !model.variant().contains("turbo");
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
            "engine: {} threads, task {:?}, whisper run extension: {}",
            threads,
            task,
            run_ext.is_some()
        );
        let options = transcribe_cpp::SessionOptions {
            n_threads: threads,
            ..Default::default()
        };
        let session = model.session_with(&options).map_err(|e| e.to_string())?;
        let bounded_window_samples = if is_whisper {
            None
        } else {
            let native_limit = session
                .limits()
                .ok()
                .map(|limits| limits.effective_max_audio_ms)
                .filter(|limit| *limit > 0)
                .map(|limit| {
                    (limit as usize).saturating_mul(crate::long_audio::SAMPLE_RATE) / 1000
                });
            let safe_limit = native_limit
                .unwrap_or(crate::long_audio::DEFAULT_MAX_WINDOW_SAMPLES)
                .min(crate::long_audio::DEFAULT_MAX_WINDOW_SAMPLES);
            let final_silence = crate::audio_finalization::final_decode_silence_samples();
            if safe_limit < final_silence.saturating_add(crate::long_audio::MIN_WINDOW_SAMPLES) {
                return Err("model reports an unusably short audio limit".to_string());
            }
            Some(safe_limit - final_silence)
        };
        ACTIVE_THREADS.store(threads, Ordering::Relaxed);
        Ok(Engine {
            session,
            language,
            task,
            english_only_model,
            translates_to_english,
            run_ext,
            supports_initial_prompt,
            custom_terms_path: files_dir.join(crate::custom_terms::FILE_NAME),
            files_dir,
            bounded_window_samples,
            ready_status,
        })
    }

    /// Transcribes one complete 16 kHz mono utterance. Whisper owns its native
    /// long-input context. Other offline families stay single-pass for normal
    /// dictation and use overlapping, context-reconciled windows only beyond
    /// their safe compute bound.
    pub fn transcribe(&mut self, samples: Vec<f32>) -> Result<String, String> {
        let mut job = self.begin_transcription();
        self.transcribe_into(&mut job, &samples)?;
        Ok(job.finish())
    }

    pub(crate) fn begin_transcription(&self) -> TranscriptionJob {
        // Snapshot once per complete transcription. Only models with prompt
        // support receive a recognition hint, but every model can restore the
        // spelling of a recognized preferred term after decoding. A prompt is
        // not a guarantee that the decoder will use or retain a term.
        let preferred_terms = crate::custom_terms::load_terms(&self.custom_terms_path);
        let prompt = if self.supports_initial_prompt {
            preferred_terms
                .as_deref()
                .and_then(crate::custom_terms::compile_prompt)
        } else {
            None
        };
        // Exact personal corrections are model-independent, including for
        // Parakeet. Snapshot them beside the Whisper prompt.
        TranscriptionJob {
            corrections: crate::personal_corrections::load(&self.files_dir),
            preferred_terms,
            run_ext: self.run_extension(prompt),
            text: String::new(),
            english_output: true,
        }
    }

    /// Adds one complete utterance to a message job.
    pub(crate) fn transcribe_into(
        &mut self,
        job: &mut TranscriptionJob,
        samples: &[f32],
    ) -> Result<(), String> {
        if let Some(max_window_samples) = self.bounded_window_samples {
            let windows = crate::long_audio::windows(samples, max_window_samples);
            let mut assembled = String::new();
            for (index, window) in windows.iter().enumerate() {
                let mut owned;
                let audio = if index + 1 < windows.len() {
                    owned = samples[window.clone()].to_vec();
                    crate::audio_finalization::append_final_decode_silence(&mut owned);
                    owned.as_slice()
                } else {
                    &samples[window.clone()]
                };
                let transcript = self.run(audio, &job.run_ext)?;
                if !transcript.text.trim().is_empty() {
                    job.english_output &= self.output_is_english(transcript.language.as_deref());
                }
                assembled = crate::long_audio::stitch(&assembled, transcript.text.trim());
            }
            append_piece(&mut job.text, &assembled);
        } else {
            let transcript = self.run(samples, &job.run_ext)?;
            if !transcript.text.trim().is_empty() {
                job.english_output &= self.output_is_english(transcript.language.as_deref());
            }
            append_piece(&mut job.text, transcript.text.trim());
        }
        Ok(())
    }
}

fn append_piece(target: &mut String, piece: &str) {
    let piece = piece.trim();
    if !piece.is_empty() {
        if !target.is_empty() {
            target.push(' ');
        }
        target.push_str(piece);
    }
}

impl Engine {
    fn output_is_english(&self, detected: Option<&str>) -> bool {
        crate::english_fillers::known_english_output(
            detected,
            self.language.as_deref(),
            self.english_only_model,
            self.translates_to_english,
        )
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
    ) -> Result<transcribe_cpp::Transcript, String> {
        loop {
            let opts = transcribe_cpp::RunOptions {
                language: self.language.clone(),
                task: self.task,
                family: run_ext.clone(),
                ..Default::default()
            };
            match self.session.run(samples, &opts) {
                Ok(transcript) => return Ok(transcript),
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

fn install_engine(engine: Engine) {
    *GLOBAL_ENGINE.lock().unwrap() = Some(Arc::new(Mutex::new(engine)));
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
    ACTIVE_THREADS.store(0, Ordering::Relaxed);
    *state = LoadState::Idle;
}

/// Thread count used by the loaded session. Exposed only for content-free,
/// opt-in performance diagnostics.
pub fn active_thread_count() -> i32 {
    ACTIVE_THREADS.load(Ordering::Relaxed)
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

/// Energy-conscious inference thread count: performance cores, capped at 2.
///
/// Phone SoCs are heterogeneous: fast cores paired with slow efficiency
/// cores. ggml synchronizes all threads after each operation, so a thread
/// on a slow core stalls the whole pool, and using every core is slower
/// than using only the fast ones. Cores are classified by their maximum
/// frequency from sysfs: within 70% of the fastest core counts as fast.
/// The count is capped at 2 because the Pixel 6a power benchmark showed that
/// four threads saved only about 0.1 seconds on an 11-second utterance while
/// consuming about 41% more process CPU time than two. Users can still opt in
/// to a higher value in the Models screen when latency matters more than
/// battery life. If sysfs is unreadable, the same conservative cap applies.
/// The `model_threads` config file overrides the heuristic.
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
            let threads = balanced_thread_count(fast);
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
            .unwrap_or(2)
            .min(2) as i32,
    }
}

fn balanced_thread_count(performance_cores: usize) -> i32 {
    performance_cores.clamp(1, 2) as i32
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
                install_engine(engine);
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
        // A compact first install intentionally has no bundled GGUF. Show the
        // user what to do, not an internal asset-loader error in the keyboard.
        let msg = if e.to_string() == assets::MISSING_BUNDLED_MODEL_ERROR {
            "No speech model installed. Open the app to download one.".to_string()
        } else {
            format!("Asset error: {}", e)
        };
        notify_status(env, context, &format!("Error: {}", msg));
        msg
    })?;

    notify_status(env, context, "Loading model...");

    match Engine::load(&path, language, translate, threads, files_dir) {
        Ok(engine) => {
            let status = engine.ready_status;
            install_engine(engine);
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

#[cfg(test)]
mod tests {
    use super::{balanced_thread_count, TranscriptionJob};

    #[test]
    fn automatic_inference_balances_latency_and_battery() {
        assert_eq!(balanced_thread_count(0), 1);
        assert_eq!(balanced_thread_count(1), 1);
        assert_eq!(balanced_thread_count(2), 2);
        assert_eq!(balanced_thread_count(4), 2);
        assert_eq!(balanced_thread_count(8), 2);
    }

    #[test]
    fn final_text_removes_fillers_only_from_known_english_output() {
        let job = |english_output| TranscriptionJob {
            run_ext: None,
            preferred_terms: None,
            corrections: None,
            text: "I uh think so".to_string(),
            english_output,
        };
        assert_eq!(job(true).finish(), "I think so");
        assert_eq!(job(false).finish(), "I uh think so");
    }
}
