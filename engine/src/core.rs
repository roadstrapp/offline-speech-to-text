//! The engine proper: one loaded model, one utterance in, text out.

use std::path::Path;
use std::time::Instant;

use transcribe_cpp::{CancelToken, Error as NativeError, Model, RunOptions, Session, SessionOptions};

/// The only sample rate the engine accepts (mono, f32, -1.0..=1.0).
pub const SAMPLE_RATE: usize = 16_000;
/// Right context appended before decoding. Offline decoders need a little to
/// reliably emit the last tokens; this is inference-only silence, never mic time.
pub const TRAILING_SILENCE_SAMPLES: usize = SAMPLE_RATE / 2;
/// Shortest utterance worth decoding (100 ms).
pub const MIN_UTTERANCE_SAMPLES: usize = SAMPLE_RATE / 10;
/// Longest utterance accepted (30 s). Dictation and voice search are far
/// shorter; refusing beats silently windowing and stitching long audio.
pub const MAX_UTTERANCE_SAMPLES: usize = SAMPLE_RATE * 30;

#[derive(Debug, PartialEq, Eq)]
pub enum EngineError {
    /// No file at the model path.
    ModelMissing(String),
    /// The file exists but the loader refused it (corrupt, wrong arch, OOM...).
    ModelRejected(String),
    /// The audio is unusable: empty, too short/long, or contains NaN/inf.
    InvalidAudio(String),
    /// `CancelToken::cancel` was called during the run.
    Cancelled,
    /// The native runtime failed while decoding.
    Inference(String),
}

impl std::fmt::Display for EngineError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            EngineError::ModelMissing(m) => write!(f, "model file not found: {m}"),
            EngineError::ModelRejected(m) => write!(f, "model rejected: {m}"),
            EngineError::InvalidAudio(m) => write!(f, "invalid audio: {m}"),
            EngineError::Cancelled => write!(f, "transcription cancelled"),
            EngineError::Inference(m) => write!(f, "inference failed: {m}"),
        }
    }
}

impl std::error::Error for EngineError {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Transcription {
    pub text: String,
    /// Language the model detected, when it reports one (none with a hint).
    pub language: Option<String>,
    /// Length of the submitted audio, without the trailing silence.
    pub audio_ms: u64,
    pub decode_ms: u64,
}

pub struct SttEngine {
    session: Session,
    language: Option<String>,
    max_samples: usize,
}

impl SttEngine {
    /// Loads a GGUF model. `language` is a hint (`it`, `de-DE`...); a hint the
    /// model rejects is degraded per run, never fatal. `threads == 0` picks the
    /// device's energy-conscious default.
    pub fn load(
        model_path: &Path,
        language: Option<String>,
        threads: usize,
        cancel: &CancelToken,
    ) -> Result<SttEngine, EngineError> {
        if !model_path.is_file() {
            return Err(EngineError::ModelMissing(model_path.display().to_string()));
        }
        // The GGUF is untrusted input: contain a panic in the loader.
        let model = std::panic::catch_unwind(|| Model::load(model_path))
            .map_err(|_| EngineError::ModelRejected("the model loader panicked".to_string()))?
            .map_err(|e| EngineError::ModelRejected(e.to_string()))?;
        let threads = if threads == 0 { crate::threads::auto_threads() } else { threads };
        let mut session = model
            .session_with(&SessionOptions { n_threads: threads as i32, ..Default::default() })
            .map_err(|e| EngineError::ModelRejected(e.to_string()))?;
        session.set_cancel_token(cancel);
        let native_limit = session
            .limits()
            .ok()
            .map(|l| l.effective_max_audio_ms)
            .filter(|ms| *ms > 0)
            .map(|ms| (ms as usize).saturating_mul(SAMPLE_RATE) / 1000);
        let max_samples = native_limit
            .unwrap_or(MAX_UTTERANCE_SAMPLES)
            .min(MAX_UTTERANCE_SAMPLES);
        if max_samples < TRAILING_SILENCE_SAMPLES + MIN_UTTERANCE_SAMPLES {
            return Err(EngineError::ModelRejected(
                "the model reports an unusably short audio limit".to_string(),
            ));
        }
        log::info!("engine ready: {threads} threads, up to {} s of audio", max_samples / SAMPLE_RATE);
        Ok(SttEngine {
            session,
            language: language.filter(|l| !l.is_empty() && !l.eq_ignore_ascii_case("auto")),
            max_samples: max_samples - TRAILING_SILENCE_SAMPLES,
        })
    }

    /// Longest audio (in samples) one call accepts.
    pub fn max_samples(&self) -> usize {
        self.max_samples
    }

    /// Transcribes one complete utterance (16 kHz mono f32). Blocking.
    pub fn transcribe(&mut self, pcm: &[f32]) -> Result<Transcription, EngineError> {
        validate(pcm, self.max_samples)?;
        let mut padded = Vec::with_capacity(pcm.len() + TRAILING_SILENCE_SAMPLES);
        padded.extend(pcm.iter().map(|s| s.clamp(-1.0, 1.0)));
        padded.resize(pcm.len() + TRAILING_SILENCE_SAMPLES, 0.0);
        let started = Instant::now();
        let transcript = self.run(&padded)?;
        Ok(Transcription {
            text: transcript.text.trim().to_string(),
            language: transcript.language,
            audio_ms: (pcm.len() * 1000 / SAMPLE_RATE) as u64,
            decode_ms: started.elapsed().as_millis() as u64,
        })
    }

    /// One model run. A rejected language hint is degraded rather than failing
    /// the transcription: `de-DE` retries as `de`, then with no hint (each model
    /// knows a different tag set). The degraded value is kept for later runs.
    fn run(&mut self, samples: &[f32]) -> Result<transcribe_cpp::Transcript, EngineError> {
        loop {
            let options = RunOptions { language: self.language.clone(), ..Default::default() };
            match self.session.run(samples, &options) {
                Ok(transcript) => return Ok(transcript),
                Err(NativeError::Unsupported(reason)) if self.language.is_some() => {
                    let hint = self.language.take().unwrap_or_default();
                    self.language = hint.split_once('-').map(|(primary, _)| primary.to_string());
                    log::warn!("language hint '{hint}' rejected ({reason}); retrying with {:?}", self.language);
                }
                Err(NativeError::Aborted { .. }) => return Err(EngineError::Cancelled),
                Err(other) => return Err(EngineError::Inference(other.to_string())),
            }
        }
    }
}

/// Input checks shared with the unit tests (no model needed).
pub fn validate(pcm: &[f32], max_samples: usize) -> Result<(), EngineError> {
    if pcm.len() < MIN_UTTERANCE_SAMPLES {
        return Err(EngineError::InvalidAudio(format!(
            "{} samples is shorter than the 100 ms minimum",
            pcm.len()
        )));
    }
    if pcm.len() > max_samples {
        return Err(EngineError::InvalidAudio(format!(
            "{} s is longer than the {} s limit",
            pcm.len() / SAMPLE_RATE,
            max_samples / SAMPLE_RATE
        )));
    }
    if pcm.iter().any(|s| !s.is_finite()) {
        return Err(EngineError::InvalidAudio("audio contains NaN or infinite samples".to_string()));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_too_short_too_long_and_non_finite_audio() {
        let max = MAX_UTTERANCE_SAMPLES;
        assert!(matches!(validate(&[], max), Err(EngineError::InvalidAudio(_))));
        assert!(matches!(validate(&vec![0.0; MIN_UTTERANCE_SAMPLES - 1], max), Err(EngineError::InvalidAudio(_))));
        assert!(matches!(validate(&vec![0.0; max + 1], max), Err(EngineError::InvalidAudio(_))));
        let mut bad = vec![0.0; MIN_UTTERANCE_SAMPLES];
        bad[7] = f32::NAN;
        assert!(matches!(validate(&bad, max), Err(EngineError::InvalidAudio(_))));
        bad[7] = f32::INFINITY;
        assert!(matches!(validate(&bad, max), Err(EngineError::InvalidAudio(_))));
    }

    #[test]
    fn accepts_the_exact_bounds() {
        let max = MAX_UTTERANCE_SAMPLES;
        assert_eq!(validate(&vec![0.1; MIN_UTTERANCE_SAMPLES], max), Ok(()));
        assert_eq!(validate(&vec![0.1; max], max), Ok(()));
    }

    #[test]
    fn missing_model_is_reported_before_touching_the_native_loader() {
        let result = SttEngine::load(Path::new("/nonexistent/model.gguf"), None, 1, &CancelToken::new());
        assert!(matches!(result, Err(EngineError::ModelMissing(_))));
    }

    #[test]
    fn a_non_gguf_file_is_rejected_not_fatal() {
        let path = std::env::temp_dir().join(format!("offlinestt-not-a-model-{}.gguf", std::process::id()));
        std::fs::write(&path, b"this is not a GGUF file").unwrap();
        let result = SttEngine::load(&path, None, 1, &CancelToken::new());
        std::fs::remove_file(&path).ok();
        assert!(matches!(result, Err(EngineError::ModelRejected(_))), "{:?}", result.err());
    }
}
