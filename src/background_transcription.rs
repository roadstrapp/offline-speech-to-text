//! Lossless deferred decoding for complete dictation.
//!
//! Capture and inference deliberately stay separate: the real-time audio
//! callback only appends samples. One process-private worker receives the
//! complete utterance at stop and performs one engine job. Independent
//! pause-sized runs are intentionally forbidden because they lose linguistic
//! context and can insert false punctuation between parts of one sentence;
//! only the engine may apply bounded, overlapping long-input windows.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Instant;

use crossbeam_channel::{Receiver, Sender};
use jni::objects::GlobalRef;

use crate::audio_finalization::append_final_decode_silence;
use crate::engine;

const SAMPLE_RATE: u64 = 16_000;

enum Command {
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
    while let Ok(command) = commands.recv() {
        if cancelled.load(Ordering::SeqCst) || matches!(command, Command::Cancel) {
            let _ = completion.send(Completion::Cancelled);
            return;
        }

        let (mut audio, total_samples) = match command {
            Command::Finish(audio, total_samples) => (audio, total_samples),
            Command::Cancel => unreachable!(),
        };

        if audio.is_empty() {
            let _ = completion.send(Completion::Finished {
                text: Err("no audio was captured".to_string()),
                audio_ms: 0,
                compute_ms: 0,
            });
            return;
        }
        append_final_decode_silence(&mut audio);

        let mut compute_ms = 0;
        let result = prepare_job(&jvm, &target).and_then(|(eng, mut job)| {
            let started = Instant::now();
            let result = transcribe_complete(&eng, &mut job, &audio).map(|()| job.finish());
            compute_ms = started.elapsed().as_millis() as u64;
            result
        });

        if cancelled.load(Ordering::SeqCst) {
            let _ = completion.send(Completion::Cancelled);
            return;
        }

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

fn transcribe_complete(
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
