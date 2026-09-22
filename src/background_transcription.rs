//! Lossless background decoding for long dictation.
//!
//! Capture and inference deliberately stay separate: the real-time audio
//! callback only appends samples and occasionally moves a completed chunk into
//! an unbounded in-process channel. One worker serializes model access, retains
//! every original PCM chunk until final delivery, and falls back to one
//! complete-message decode if any speculative background run fails.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Instant;

use crossbeam_channel::{Receiver, Sender};
use jni::objects::GlobalRef;

use crate::audio_finalization::append_final_decode_silence;
use crate::engine;

const SAMPLE_RATE: u64 = 16_000;

enum Command {
    Chunk(Vec<f32>),
    Finish(Vec<f32>, u64),
    Cancel,
}

pub(crate) enum Completion {
    Finished {
        text: Result<String, String>,
        audio_ms: u64,
        compute_ms: u64,
    },
    Cancelled,
}

pub(crate) struct BackgroundTranscriber {
    commands: Sender<Command>,
    completion: Receiver<Completion>,
    cancelled: Arc<AtomicBool>,
    terminal_sent: AtomicBool,
}

impl BackgroundTranscriber {
    pub(crate) fn start(jvm: Arc<jni::JavaVM>, target: GlobalRef) -> Self {
        let (command_tx, command_rx) = crossbeam_channel::unbounded();
        let (completion_tx, completion_rx) = crossbeam_channel::bounded(1);
        let cancelled = Arc::new(AtomicBool::new(false));
        let worker_cancelled = cancelled.clone();
        std::thread::spawn(move || {
            worker_loop(command_rx, completion_tx, worker_cancelled, jvm, target)
        });
        Self {
            commands: command_tx,
            completion: completion_rx,
            cancelled,
            terminal_sent: AtomicBool::new(false),
        }
    }

    pub(crate) fn submit(&self, chunk: Vec<f32>) -> Result<(), Vec<f32>> {
        self.commands
            .send(Command::Chunk(chunk))
            .map_err(|error| match error.0 {
                Command::Chunk(chunk) => chunk,
                _ => unreachable!("submit only sends chunks"),
            })
    }

    pub(crate) fn finish(
        &self,
        tail: Vec<f32>,
        total_samples: u64,
    ) -> Option<Receiver<Completion>> {
        if self
            .terminal_sent
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_err()
        {
            return None;
        }
        if let Err(error) = self.commands.send(Command::Finish(tail, total_samples)) {
            log::error!(
                "background transcription worker ended before finish: {}",
                error
            );
        }
        Some(self.completion.clone())
    }

    pub(crate) fn cancel(&self) -> Option<Receiver<Completion>> {
        if self
            .terminal_sent
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_err()
        {
            return None;
        }
        self.cancelled.store(true, Ordering::SeqCst);
        let _ = self.commands.send(Command::Cancel);
        Some(self.completion.clone())
    }
}

fn worker_loop(
    commands: Receiver<Command>,
    completion: Sender<Completion>,
    cancelled: Arc<AtomicBool>,
    jvm: Arc<jni::JavaVM>,
    target: GlobalRef,
) {
    let mut retained_audio: Vec<Vec<f32>> = Vec::new();
    let mut engine_and_job: Option<(
        Arc<std::sync::Mutex<engine::Engine>>,
        engine::TranscriptionJob,
    )> = None;
    let mut background_error: Option<String> = None;
    let mut compute_ms = 0u64;

    while let Ok(command) = commands.recv() {
        if cancelled.load(Ordering::SeqCst) || matches!(command, Command::Cancel) {
            let _ = completion.send(Completion::Cancelled);
            return;
        }

        let (mut audio, finish) = match command {
            Command::Chunk(audio) => (audio, None),
            Command::Finish(audio, total_samples) => (audio, Some(total_samples)),
            Command::Cancel => unreachable!(),
        };

        if !audio.is_empty() {
            let original_len = audio.len();
            append_final_decode_silence(&mut audio);
            if background_error.is_none() {
                if engine_and_job.is_none() {
                    match prepare_job(&jvm, &target) {
                        Ok(value) => engine_and_job = Some(value),
                        Err(error) => background_error = Some(error),
                    }
                }
                if let Some((eng, job)) = engine_and_job.as_mut() {
                    let started = Instant::now();
                    if let Err(error) = transcribe_chunk(eng, job, &audio) {
                        background_error = Some(error);
                    }
                    compute_ms = compute_ms.saturating_add(started.elapsed().as_millis() as u64);
                }
            }
            audio.truncate(original_len);
            retained_audio.push(audio);
        }

        let Some(total_samples) = finish else {
            continue;
        };

        if cancelled.load(Ordering::SeqCst) {
            let _ = completion.send(Completion::Cancelled);
            return;
        }

        let result = if background_error.is_none() {
            engine_and_job
                .take()
                .map(|(_, job)| job.finish())
                .ok_or_else(|| "no audio was captured".to_string())
        } else {
            log::warn!("background chunk failed; retrying the retained complete recording");
            retry_complete_recording(
                &jvm,
                &target,
                &mut retained_audio,
                engine_and_job.take(),
                &mut compute_ms,
            )
        };

        let _ = completion.send(Completion::Finished {
            text: result,
            audio_ms: total_samples.saturating_mul(1000) / SAMPLE_RATE,
            compute_ms,
        });
        return;
    }

    let _ = completion.send(Completion::Cancelled);
}

fn prepare_job(
    jvm: &Arc<jni::JavaVM>,
    target: &GlobalRef,
) -> Result<
    (
        Arc<std::sync::Mutex<engine::Engine>>,
        engine::TranscriptionJob,
    ),
    String,
> {
    if engine::get_engine().is_none() {
        engine::ensure_loaded_from_thread(jvm, target)?;
    }
    let eng = engine::get_engine().ok_or_else(|| "model not loaded".to_string())?;
    let job = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        eng.lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .begin_transcription()
    }))
    .map_err(|_| "transcription setup failed unexpectedly".to_string())?;
    Ok((eng, job))
}

fn transcribe_chunk(
    engine: &Arc<std::sync::Mutex<engine::Engine>>,
    job: &mut engine::TranscriptionJob,
    audio: &[f32],
) -> Result<(), String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        engine
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .transcribe_into(job, audio)
    }))
    .unwrap_or_else(|_| Err("transcription failed unexpectedly, please try again".to_string()))
}

fn retry_complete_recording(
    jvm: &Arc<jni::JavaVM>,
    target: &GlobalRef,
    retained_audio: &mut Vec<Vec<f32>>,
    existing: Option<(
        Arc<std::sync::Mutex<engine::Engine>>,
        engine::TranscriptionJob,
    )>,
    compute_ms: &mut u64,
) -> Result<String, String> {
    let total_len: usize = retained_audio.iter().map(Vec::len).sum();
    let mut complete = Vec::with_capacity(total_len.saturating_add(8_000));
    for chunk in retained_audio.drain(..) {
        complete.extend_from_slice(&chunk);
    }
    append_final_decode_silence(&mut complete);

    let (eng, mut job) = match existing {
        Some((eng, mut job)) => {
            job.clear_text();
            (eng, job)
        }
        None => prepare_job(jvm, target)?,
    };
    let started = Instant::now();
    let result = transcribe_chunk(&eng, &mut job, &complete).map(|()| job.finish());
    *compute_ms = compute_ms.saturating_add(started.elapsed().as_millis() as u64);
    result
}
