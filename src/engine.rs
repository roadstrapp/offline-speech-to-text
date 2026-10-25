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
use std::sync::atomic::{AtomicI32, AtomicU64, Ordering};
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
/// File in filesDir listing the languages the user speaks (`en,de`). With two or more and the
/// language-ID model installed, each recognition run detects among exactly those languages and
/// uses the winner as its hint. Otherwise only `model_language` applies.
const MODEL_LANGUAGES_FILE: &str = "model_languages";
/// Where the downloaded language-ID model lives, relative to filesDir. Kept out of `models/`
/// so it can never be selected as the speech model.
pub(crate) const LANGUAGE_ID_MODEL_PATH: &str = "aux-models/lang-id-voxlingua107-ecapa-Q8_0.gguf";
/// Marker file in filesDir: when present, models that support translation
/// (e.g. Whisper) translate speech to English instead of transcribing it.
/// Ignored by models without translation support.
const MODEL_TRANSLATE_FILE: &str = "model_translate";
/// Optional file in filesDir with the CPU thread count for inference.
/// Absent/invalid/0 = default (all cores).
const MODEL_THREADS_FILE: &str = "model_threads";
pub(crate) static INFERENCE_GATE: Lazy<crate::inference_gate::Gate> =
    Lazy::new(crate::inference_gate::Gate::default);
static NEXT_ENGINE_GENERATION: AtomicU64 = AtomicU64::new(1);
static ACTIVE_THREADS: AtomicI32 = AtomicI32::new(0);

/// Settings captured once for a complete user transcription.
#[derive(Clone)]
pub(crate) struct TranscriptionJob {
    run_ext: Option<transcribe_cpp::RunExtension>,
    preferred_terms: Option<Vec<String>>,
    corrections: Option<Vec<crate::personal_corrections::Rule>>,
    text: String,
    output_language: crate::transcript_cleanup::OutputLanguage,
    /// Private hypotheses for immutable existing long-input windows. Never
    /// delivered piecemeal or joined at a silence-only boundary.
    prepared_windows: usize,
    prepared_text: String,
}

impl TranscriptionJob {
    pub(crate) fn finish(self) -> String {
        crate::transcript_cleanup::finish(
            self.text,
            self.output_language,
            self.preferred_terms.as_deref(),
            self.corrections.as_deref(),
        )
    }
}

/// Picks the spoken language among the user's own languages, which plain automatic detection
/// cannot be told (closely related languages are easily mistaken for each other).
struct LanguageDetector {
    session: transcribe_cpp::LangIdSession,
    /// Detector labels for the user's languages.
    allowed: Vec<String>,
    /// Last allowed recognition code; the first selected language is used before any result.
    previous: String,
    min_samples: usize,
}

impl LanguageDetector {
    fn open(path: &Path, languages: &[String], threads: i32) -> Result<LanguageDetector, String> {
        let model = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            transcribe_cpp::Model::load(path)
        }))
        .map_err(|_| "language model loader rejected this file".to_string())?
        .map_err(|e| e.to_string())?;
        let info = model.langid_info().map_err(|e| e.to_string())?;
        let session = model
            .langid_session_with(&transcribe_cpp::LangIdSessionOptions { n_threads: threads })
            .map_err(|e| e.to_string())?;
        Ok(LanguageDetector {
            session,
            allowed: languages
                .iter()
                .map(|code| crate::language_selection::detector_label(code).to_string())
                .collect(),
            previous: languages[0].clone(),
            min_samples: (info.min_audio_ms.max(0) as usize).saturating_mul(16),
        })
    }

    /// The most likely of the user's languages for this clip, as a recognition hint.
    fn detect(&mut self, samples: &[f32]) -> String {
        if samples.len() >= self.min_samples {
            let options = transcribe_cpp::LangIdOptions {
                allowed: Some(self.allowed.clone()),
            };
            match self.session.run(samples, &options) {
                Ok(result) => {
                    if let Some(code) = result.code().filter(|code| self.allowed.iter().any(|allowed| allowed == code)) {
                        self.previous = crate::language_selection::recognition_code(code).to_string();
                    }
                }
                Err(e) => log::warn!("language detection failed ({e}); keeping {}", self.previous),
            }
        }
        self.previous.clone()
    }
}

/// A loaded transcribe.cpp session plus the options applied to every run.
pub struct Engine {
    model_path: PathBuf,
    generation: u64,
    threads: i32,
    diagnostic_family: i32,
    session: transcribe_cpp::Session,
    language: Option<String>,
    /// Present when the user speaks several languages and the language-ID model is installed.
    detector: Option<LanguageDetector>,
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
        allowed_languages: &[String],
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
        let capabilities = model.capabilities().ok();
        let task = if translate && capabilities.as_ref().is_some_and(|c| c.supports_translate) {
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
        let english_only_model = capabilities.as_ref()
            .is_some_and(|c| crate::language_selection::english_only(&c.languages));
        use crate::language_selection::ModelLanguagePolicy;
        let language_policy = crate::language_selection::model_policy(
            &model.arch(), capabilities.as_ref().map(|c| c.languages.as_slice()), allowed_languages
        )?;
        let language = if language_policy == ModelLanguagePolicy::EnglishOnly {
            Some("en".to_string())
        } else { language };
        let ready_status = if language_policy == ModelLanguagePolicy::IgnoresList {
            "Ready (this model ignores Languages I speak)"
        } else if language_policy == ModelLanguagePolicy::EnglishOnly {
            "Ready (this model supports English only)"
        } else if translate && task == transcribe_cpp::Task::Transcribe {
            "Ready (this model can't translate)"
        } else if translate && model.variant().contains("turbo") {
            "Ready (note: Whisper Turbo translates poorly; use another Whisper)"
        } else { "Ready" };
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
        let detector = if language_policy == ModelLanguagePolicy::Detect {
            open_detector(&files_dir, allowed_languages, threads)?
        } else { None };
        ACTIVE_THREADS.store(threads, Ordering::Relaxed);
        Ok(Engine {
            model_path: model_path.to_path_buf(),
            generation: NEXT_ENGINE_GENERATION.fetch_add(1, Ordering::Relaxed),
            threads,
            diagnostic_family: match model.arch().as_str() {
                "whisper" => 1,
                "parakeet" => 2,
                "moonshine" => 3,
                "gigaam" => 4,
                "medasr" => 5,
                _ => 0,
            },
            session,
            language,
            detector,
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
        self.transcribe_into(&mut job, &samples, false)?;
        Ok(job.finish())
    }

    pub(crate) fn begin_transcription(&self) -> TranscriptionJob {
        // Snapshot once per complete transcription. Only models with prompt
        // support receive a recognition hint, but every model can restore the
        // spelling of a recognized preferred term after decoding. A prompt is
        // not a guarantee that the decoder will use or retain a term.
        let (preferred_terms, corrections) = self.snapshot_cleanup_terms();
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
            corrections,
            preferred_terms,
            run_ext: self.run_extension(prompt),
            text: String::new(),
            output_language: crate::transcript_cleanup::OutputLanguage::English,
            prepared_windows: 0,
            prepared_text: String::new(),
        }
    }

    /// Plans windows over raw audio; final-only silence never creates a boundary.
    /// Partial subtitle decoding passes false to preserve its existing latency contract.
    pub(crate) fn transcribe_into(
        &mut self,
        job: &mut TranscriptionJob,
        samples: &[f32],
        pad_final: bool,
    ) -> Result<(), String> {
        if let Some(max_window_samples) = self.bounded_window_samples {
            let windows = crate::long_audio::windows(samples, max_window_samples);
            if job.prepared_windows >= windows.len() {
                // Original audio is authoritative. An unusable cache must
                // never turn a recoverable recording into an error or loss.
                job.prepared_windows = 0;
                job.prepared_text.clear();
                job.output_language = crate::transcript_cleanup::OutputLanguage::English;
            }
            let prepared_windows = std::mem::take(&mut job.prepared_windows);
            let mut assembled = std::mem::take(&mut job.prepared_text);
            for (index, window) in windows.iter().enumerate() {
                if index < prepared_windows {
                    continue;
                }
                self.decode_window(
                    job,
                    &samples[window.clone()],
                    index + 1 < windows.len() || pad_final,
                    &mut assembled,
                )?;
            }
            append_piece(&mut job.text, &assembled);
        } else {
            let mut audio;
            let samples = if pad_final {
                audio = samples.to_vec();
                crate::audio_finalization::append_final_decode_silence(&mut audio);
                audio.as_slice()
            } else {
                samples
            };
            let transcript = self.run(samples, &job.run_ext)?;
            if !transcript.text.trim().is_empty() {
                job.output_language = job.output_language.combine(self.output_language(transcript.language.as_deref()));
            }
            append_piece(&mut job.text, transcript.text.trim());
        }
        Ok(())
    }

    pub(crate) fn background_window_limit(&self) -> Option<usize> {
        self.bounded_window_samples
    }

    pub(crate) fn file_window_limit(&self) -> usize {
        self.bounded_window_samples
            .unwrap_or(crate::long_audio::DEFAULT_MAX_WINDOW_SAMPLES)
    }

    pub(crate) fn finish_file_job(mut job: TranscriptionJob, text: String) -> String {
        job.text = text;
        job.finish()
    }

    pub(crate) fn diagnostic_family(&self) -> i32 {
        self.diagnostic_family
    }

    pub(crate) fn prepared_window_count(job: &TranscriptionJob) -> usize {
        job.prepared_windows
    }

    pub(crate) fn prepare_complete_window(
        &mut self,
        job: &mut TranscriptionJob,
        audio: Vec<f32>,
    ) -> Result<(), String> {
        let mut assembled = job.prepared_text.clone();
        let result = self.decode_window(job, &audio, true, &mut assembled);
        result?;
        job.prepared_text = assembled;
        job.prepared_windows += 1;
        Ok(())
    }

    pub(crate) fn refresh_final_terms(&self, job: &mut TranscriptionJob) {
        // Preparation is non-Whisper only: these terms do not alter its native
        // run options. Preserve the ordinary Stop-time cleanup snapshot.
        (job.preferred_terms, job.corrections) = self.snapshot_cleanup_terms();
    }

    fn snapshot_cleanup_terms(
        &self,
    ) -> (
        Option<Vec<String>>,
        Option<Vec<crate::personal_corrections::Rule>>,
    ) {
        (
            crate::custom_terms::load_terms(&self.custom_terms_path),
            crate::personal_corrections::load(&self.files_dir),
        )
    }

    /// Both early preparation and deferred windows use this exact path.
    pub(crate) fn decode_window(
        &mut self,
        job: &mut TranscriptionJob,
        audio: &[f32],
        pad: bool,
        assembled: &mut String,
    ) -> Result<(), String> {
        let mut owned;
        let audio = if pad {
            owned = audio.to_vec();
            crate::audio_finalization::append_final_decode_silence(&mut owned);
            owned.as_slice()
        } else {
            audio
        };
        let transcript = self.run(audio, &job.run_ext)?;
        if !transcript.text.trim().is_empty() {
            job.output_language = job.output_language.combine(self.output_language(transcript.language.as_deref()));
        }
        *assembled = crate::long_audio::stitch(assembled, transcript.text.trim());
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
    fn output_language(&self, detected: Option<&str>) -> crate::transcript_cleanup::OutputLanguage {
        use crate::transcript_cleanup::OutputLanguage;
        if self.output_is_english(detected) { OutputLanguage::English }
        else if detected.is_some() || self.language.is_some() { OutputLanguage::Other }
        else { OutputLanguage::Unknown }
    }

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
        let range = crate::endpoint::speech_range(samples);
        let samples = &samples[range];
        if samples.is_empty() {
            return Ok(transcribe_cpp::Transcript::default());
        }
        // The detected language is per run: it never replaces the user's fixed hint, and a model
        // that rejects it must fail rather than silently leave the allowed set.
        let mut detected = self.detector.as_mut().map(|detector| detector.detect(samples));
        loop {
            let opts = transcribe_cpp::RunOptions {
                language: detected.clone().or_else(|| self.language.clone()),
                task: self.task,
                family: run_ext.clone(),
                ..Default::default()
            };
            match self.session.run(samples, &opts) {
                Ok(mut transcript) => {
                    // Most models do not report a language when given one; the cleanup stage
                    // needs to know which language this was.
                    if transcript.language.is_none() {
                        transcript.language = detected.take();
                    }
                    return Ok(transcript);
                }
                Err(transcribe_cpp::Error::OutputTruncated {
                    partial: Some(transcript),
                    ..
                })
                | Err(transcribe_cpp::Error::OutputRepetition {
                    partial: Some(transcript),
                    ..
                }) => {
                    let mut transcript = *transcript;
                    if transcript.language.is_none() { transcript.language = detected.take(); }
                    return Ok(transcript);
                }
                Err(transcribe_cpp::Error::Unsupported(msg)) if detected.is_some() => {
                    return Err(format!("The speech model rejected the chosen language: {msg}"));
                }
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
    let _permit = INFERENCE_GATE.acquire(false);
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

/// Exact source of the loaded engine, for bounded local qualification.
/// Never waits behind inference and is never included in public diagnostics.
pub fn loaded_model_path() -> Option<PathBuf> {
    let engine = get_engine()?;
    let guard = engine.try_lock().ok()?;
    Some(guard.model_path.clone())
}

/// Content-free, read-only qualification identity; never blocks inference or logs settings.
pub fn loaded_model_configuration() -> Option<String> {
    let engine = get_engine()?;
    let guard = engine.try_lock().ok()?;
    let languages = guard.detector.as_ref().map(|detector| detector.allowed.iter()
        .map(|code| crate::language_selection::recognition_code(code)).collect::<Vec<_>>().join(","))
        .unwrap_or_default();
    Some(format!("generation={};threads={};detector={}", guard.generation, guard.threads, languages))
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

/// A multi-language choice requires a usable detector; failure must not silently enable
/// unrestricted recognition.
fn open_detector(files_dir: &Path, languages: &[String], threads: i32) -> Result<Option<LanguageDetector>, String> {
    if languages.len() < 2 {
        return Ok(None);
    }
    let path = files_dir.join(LANGUAGE_ID_MODEL_PATH);
    if !path.is_file() {
        return Err("Download the language detector or choose a single language".to_string());
    }
    LanguageDetector::open(&path, languages, threads)
        .map(Some)
        .map_err(|_| "Language detector unavailable; download it again or choose one language".to_string())
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
    let allowed_languages = read_config(&files_dir.join(MODEL_LANGUAGES_FILE))
        .map(|list| crate::language_selection::parse_allowed(&list))
        .unwrap_or_default();
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
            &allowed_languages,
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
                if allowed_languages.len() >= 2 {
                    notify_status(env, context, &format!("Error: {}", e));
                    return Err(e);
                }
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

    match Engine::load(&path, language, &allowed_languages, translate, threads, files_dir) {
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
            output_language: if english_output { crate::transcript_cleanup::OutputLanguage::English }
                else { crate::transcript_cleanup::OutputLanguage::Other },
            prepared_windows: 0,
            prepared_text: String::new(),
        };
        assert_eq!(job(true).finish(), "I think so");
        assert_eq!(job(false).finish(), "I uh think so");
    }
}
