//! Lossless decoding for complete dictation, with identical long-window work
//! moved into capture time where the window is already immutable.
//!
//! Capture and inference deliberately stay separate: the real-time audio
//! callback only appends samples. One process-private worker receives the
//! complete utterance at stop and performs one engine job. Independent
//! pause-sized runs are intentionally forbidden because they lose linguistic
//! context and can insert false punctuation between parts of one sentence;
//! only the engine may apply bounded, overlapping long-input windows.

use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

use crossbeam_channel::{Receiver, Sender};
use jni::objects::GlobalRef;

use crate::background_chunking::CaptureBuffer;
use crate::engine;

const SAMPLE_RATE: u64 = 16_000;

enum Command {
    Prepare,
    Finish(Vec<f32>, u64, Instant),
    Cancel,
}

pub(crate) enum Completion {
    Finished {
        text: Result<String, String>,
        audio_ms: u64,
        compute_ms: u64,
        pipeline: PipelineMetrics,
    },
    Cancelled,
}

#[derive(Default)]
pub(crate) struct PipelineMetrics {
    pub session_id: i64,
    pub prepare_ms: u64,
    pub decode_ms: u64,
    pub postprocess_ms: u64,
    pub stop_wait_ms: u64,
    pub prepared_windows: usize,
    pub model_family: i32,
    pub model_warm: i32,
}

/// Fixed numeric metadata only. Older callback targets may omit this optional
/// method; clearing that lookup exception preserves normal result delivery.
pub(crate) fn notify_pipeline(
    env: &mut jni::JNIEnv,
    target: &jni::objects::JObject,
    metrics: &PipelineMetrics,
) {
    if metrics.session_id == 0 {
        return;
    }
    if env
        .call_method(
            target,
            "onPipelineMetrics",
            "(JJJJJIII)V",
            &[
                jni::objects::JValue::Long(metrics.session_id),
                jni::objects::JValue::Long(metrics.prepare_ms as i64),
                jni::objects::JValue::Long(metrics.decode_ms as i64),
                jni::objects::JValue::Long(metrics.postprocess_ms as i64),
                jni::objects::JValue::Long(metrics.stop_wait_ms as i64),
                jni::objects::JValue::Int(metrics.prepared_windows as i32),
                jni::objects::JValue::Int(metrics.model_family),
                jni::objects::JValue::Int(metrics.model_warm),
            ],
        )
        .is_err()
    {
        let _ = env.exception_clear();
    }
}

pub(crate) struct BackgroundTranscriber {
    commands: Sender<Command>,
    completion: Receiver<Completion>,
    cancelled: Arc<AtomicBool>,
    terminal_sent: Arc<AtomicBool>,
    preparation_requested: Arc<AtomicBool>,
    capture: Option<Arc<Mutex<CaptureBuffer>>>,
    window_limit: Mutex<Option<Option<usize>>>,
    next_sample: Arc<AtomicUsize>,
}

impl BackgroundTranscriber {
    pub(crate) fn start(jvm: Arc<jni::JavaVM>, target: GlobalRef) -> Self {
        Self::start_with_capture(jvm, target, None)
    }

    pub(crate) fn start_with_capture(
        jvm: Arc<jni::JavaVM>,
        target: GlobalRef,
        capture: Option<Arc<Mutex<CaptureBuffer>>>,
    ) -> Self {
        let (command_tx, command_rx) = crossbeam_channel::unbounded();
        let (completion_tx, completion_rx) = crossbeam_channel::bounded(1);
        let cancelled = Arc::new(AtomicBool::new(false));
        let worker_cancelled = cancelled.clone();
        let terminal_sent = Arc::new(AtomicBool::new(false));
        let preparation_requested = Arc::new(AtomicBool::new(false));
        let worker_terminal = terminal_sent.clone();
        let worker_requested = preparation_requested.clone();
        let worker_capture = capture.clone();
        let next_sample = Arc::new(AtomicUsize::new(0));
        let worker_next = next_sample.clone();
        // Snapshot synchronously before spawning: a later recording must not
        // receive an older job's pipeline metadata.
        let session_id = jvm
            .attach_current_thread()
            .ok()
            .and_then(|mut env| {
                match env.call_method(target.as_obj(), "getTroubleshootingSessionId", "()J", &[]) {
                    Ok(value) => value.j().ok(),
                    Err(_) => {
                        let _ = env.exception_clear();
                        None
                    }
                }
            })
            .unwrap_or(0);
        std::thread::spawn(move || {
            worker_loop(
                command_rx,
                completion_tx,
                worker_cancelled,
                jvm,
                target,
                worker_capture,
                worker_terminal,
                worker_requested,
                worker_next,
                session_id,
            )
        });
        Self {
            commands: command_tx,
            completion: completion_rx,
            cancelled,
            terminal_sent,
            preparation_requested,
            capture,
            window_limit: Mutex::new(None),
            next_sample,
        }
    }

    /// The existing capture monitor nudges us; no extra timer/wake lock and no
    /// audio work in the real-time callback. Coalesce to one queued nudge.
    pub(crate) fn request_preparation(&self) {
        let Some(capture) = &self.capture else {
            return;
        };
        let Ok(mut cached) = self.window_limit.try_lock() else {
            return;
        };
        if cached.is_none() {
            let Some(eng) = engine::get_engine() else {
                return;
            };
            let Ok(guard) = eng.try_lock() else {
                return;
            };
            *cached = Some(guard.background_window_limit());
        }
        let Some(limit) = cached.flatten() else {
            return;
        };
        let Ok(buffer) = capture.try_lock() else {
            return;
        };
        if buffer.total_samples()
            <= self
                .next_sample
                .load(Ordering::SeqCst)
                .saturating_add(limit) as u64
        {
            return;
        }
        drop(buffer);
        drop(cached);
        if !self.terminal_sent.load(Ordering::SeqCst)
            && !self.preparation_requested.swap(true, Ordering::SeqCst)
        {
            let _ = self.commands.send(Command::Prepare);
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
        if let Err(error) = self
            .commands
            .send(Command::Finish(tail, total_samples, Instant::now()))
        {
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
        // Preserve the established Stop-then-discard contract: after Finish,
        // Java consumes the final callback solely to clear its discarded UI.
        // Before Finish this fences preparation, including an in-flight run.
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
    capture: Option<Arc<Mutex<CaptureBuffer>>>,
    terminal: Arc<AtomicBool>,
    requested: Arc<AtomicBool>,
    prepared_through: Arc<AtomicUsize>,
    session_id: i64,
) {
    let mut prepared: Option<(Arc<Mutex<engine::Engine>>, engine::TranscriptionJob)> = None;
    let mut next_sample = 0;
    let mut compute_ms: u64 = 0;
    let mut preparation_failed = false;
    let mut pipeline = PipelineMetrics {
        session_id,
        ..PipelineMetrics::default()
    };
    loop {
        let command = match commands.recv() {
            Err(_) => break,
            Ok(Command::Prepare) => {
                requested.store(false, Ordering::SeqCst);
                if cancelled.load(Ordering::SeqCst) {
                    break;
                }
                if terminal.load(Ordering::SeqCst) {
                    continue;
                }
                if preparation_failed {
                    continue;
                }
                let Some(capture) = &capture else {
                    continue;
                };
                let Some(eng) = prepared
                    .as_ref()
                    .map(|(eng, _)| eng.clone())
                    .or_else(engine::get_engine)
                else {
                    continue;
                };
                let limit = match eng.try_lock() {
                    Ok(guard) => guard.background_window_limit(),
                    Err(_) => None,
                };
                let Some(limit) = limit else {
                    continue;
                };
                let window = capture
                    .try_lock()
                    .ok()
                    .and_then(|buffer| buffer.window_snapshot(next_sample, limit))
                    .and_then(|mut audio| {
                        let window = crate::long_audio::complete_window(&audio, 0, limit)?;
                        let next = next_sample
                            + window
                                .end
                                .saturating_sub(crate::long_audio::overlap_samples(limit));
                        audio.truncate(window.end);
                        Some((audio, next))
                    });
                let Some((audio, next)) = window else {
                    continue;
                };
                if cancelled.load(Ordering::SeqCst) {
                    break;
                }
                if prepared.is_none() {
                    let started = Instant::now();
                    // Bind limit, settings and decoder to this exact engine.
                    let job = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        let guard = eng.lock().unwrap_or_else(|p| p.into_inner());
                        pipeline.model_family = guard.diagnostic_family();
                        pipeline.model_warm = 1;
                        guard.begin_transcription()
                    }));
                    pipeline.prepare_ms += started.elapsed().as_millis() as u64;
                    match job {
                        Ok(job) => prepared = Some((eng, job)),
                        Err(_) => {
                            preparation_failed = true;
                            prepared_through.store(usize::MAX, Ordering::SeqCst);
                            continue;
                        }
                    }
                }
                let (eng, job) = prepared.as_mut().expect("prepared above");
                let started = Instant::now();
                let _permit = engine::INFERENCE_GATE.acquire(false);
                let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    eng.lock()
                        .unwrap_or_else(|p| p.into_inner())
                        .prepare_complete_window(job, audio)
                }));
                compute_ms = compute_ms.saturating_add(started.elapsed().as_millis() as u64);
                if matches!(result, Ok(Ok(()))) {
                    next_sample = next;
                    prepared_through.store(next, Ordering::SeqCst);
                } else {
                    preparation_failed = true;
                    prepared_through.store(usize::MAX, Ordering::SeqCst);
                    if result.is_err() {
                        // A native panic may have mutated any part of the job.
                        // Discard the entire cache; retained audio is decoded
                        // from scratch at Stop, including every earlier word.
                        prepared = None;
                    }
                }
                continue;
            }
            Ok(command) => command,
        };
        if cancelled.load(Ordering::SeqCst) || matches!(command, Command::Cancel) {
            let _ = completion.send(Completion::Cancelled);
            return;
        }

        let (audio, total_samples, stop_started) = match command {
            Command::Finish(audio, total_samples, started) => (audio, total_samples, started),
            Command::Cancel => unreachable!(),
            Command::Prepare => unreachable!(),
        };

        if audio.is_empty() {
            let _ = completion.send(Completion::Finished {
                text: Err("no audio was captured".to_string()),
                audio_ms: 0,
                compute_ms: 0,
                pipeline,
            });
            return;
        }

        let created_during_capture = prepared.is_some();
        let result = prepared
            .map(Ok)
            .unwrap_or_else(|| {
                let started = Instant::now();
                pipeline.model_warm = i32::from(engine::get_engine().is_some());
                let result = prepare_job(&jvm, &target);
                pipeline.prepare_ms += started.elapsed().as_millis() as u64;
                result
            })
            .and_then(|(eng, mut job)| {
                let started = Instant::now();
                let setup = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    let guard = eng.lock().unwrap_or_else(|p| p.into_inner());
                    pipeline.model_family = guard.diagnostic_family();
                    if created_during_capture {
                        guard.refresh_final_terms(&mut job);
                    }
                }))
                .map_err(|_| "transcription setup failed unexpectedly".to_string());
                pipeline.prepare_ms += started.elapsed().as_millis() as u64;
                setup?;
                pipeline.prepared_windows = engine::Engine::prepared_window_count(&job);
                let started = Instant::now();
                let result = transcribe_complete(&eng, &mut job, &audio);
                compute_ms = compute_ms.saturating_add(started.elapsed().as_millis() as u64);
                pipeline.decode_ms = compute_ms;
                result.map(|()| {
                    let started = Instant::now();
                    let text = job.finish();
                    pipeline.postprocess_ms = started.elapsed().as_millis() as u64;
                    text
                })
            });

        if cancelled.load(Ordering::SeqCst) {
            let _ = completion.send(Completion::Cancelled);
            return;
        }

        let _ = completion.send(Completion::Finished {
            text: result,
            audio_ms: total_samples.saturating_mul(1000) / SAMPLE_RATE,
            compute_ms: compute_ms.saturating_add(pipeline.postprocess_ms),
            pipeline: {
                pipeline.stop_wait_ms = stop_started.elapsed().as_millis() as u64;
                pipeline
            },
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
    let _permit = engine::INFERENCE_GATE.acquire(true);
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        engine
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .transcribe_into(job, audio, true)
    }))
    .unwrap_or_else(|_| Err("transcription failed unexpectedly, please try again".to_string()))
}
