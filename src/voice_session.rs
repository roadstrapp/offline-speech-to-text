use std::sync::atomic::{AtomicBool, AtomicU64, AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use cpal::traits::HostTrait;
use jni::objects::{GlobalRef, JObject, JValue};
use jni::JNIEnv;

use crate::background_chunking::CaptureBuffer;
use crate::background_transcription::{BackgroundTranscriber, Completion};
use crate::engine;
use crate::interactive_endpointing::should_finish_interactive_session;

pub const OWNER_IME: u8 = 1;
pub const OWNER_ACTIVITY: u8 = 2;
pub const OWNER_FOREGROUND: u8 = 3;
pub const OWNER_RECOGNITION_SERVICE: u8 = 4;
static SESSION_OWNER: AtomicU8 = AtomicU8::new(0);

pub fn claim_session_owner(owner: u8) -> bool {
    SESSION_OWNER
        .compare_exchange(0, owner, Ordering::SeqCst, Ordering::SeqCst)
        .is_ok()
}

pub fn release_session_owner(owner: u8) {
    let _ = SESSION_OWNER.compare_exchange(owner, 0, Ordering::SeqCst, Ordering::SeqCst);
}

// --- Optional auto-stop endpointing (same level heuristics as recog_service) --
const LEVEL_UPDATE_MS: u64 = 100;
const AUTO_STOP_MONITOR_MS: u64 = 250;
const MANUAL_MONITOR_MS: u64 = 1000;
const CAPTURE_HEALTH_MONITOR_MS: u64 = 250;
const DIGITAL_ZERO_TIMEOUT: Duration = Duration::from_secs(2);

fn has_two_seconds_of_digital_zero(since_last_nonzero: Duration) -> bool {
    since_last_nonzero >= DIGITAL_ZERO_TIMEOUT
}

/// The recording's microphone; it owns the optional Bluetooth route and its
/// recovery (see managed_capture.rs).
pub struct SendStream(pub crate::managed_capture::ManagedCapture);
unsafe impl Send for SendStream {}
unsafe impl Sync for SendStream {}

/// Speech/silence tracking shared between the audio callback and the
/// auto-stop monitor thread.
struct Endpointing {
    /// Milliseconds since this capture started. The audio callback has a
    /// single writer, so an atomic avoids taking a mutex on every block.
    last_voice_ms: AtomicU64,
    speech_started: AtomicBool,
    /// The system microphone toggle supplies digital zero while access is
    /// blocked. The no-speech timer must start only after real samples arrive.
    audio_started: AtomicBool,
}

pub struct VoiceSessionState {
    pub stream: Option<SendStream>,
    audio_buffer: Arc<Mutex<CaptureBuffer>>,
    pub jvm: Arc<jni::JavaVM>,
    pub target_ref: GlobalRef,
    /// True while the current recording runs; flipped off on stop/cancel so
    /// the auto-stop monitor (if any) exits.
    pub session_active: Arc<AtomicBool>,
    /// Drops callback samples while the IME is paused. The native stream is
    /// also suspended when supported; this gate is the reliable fallback.
    capture_paused: Arc<AtomicBool>,
    owner: u8,
    owns_session: Arc<AtomicBool>,
    inference_running: Arc<AtomicBool>,
    background: Option<Arc<BackgroundTranscriber>>,
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

fn notify_level(env: &mut JNIEnv, obj: &JObject, level: f32) {
    let _ = env.call_method(obj, "onAudioLevel", "(F)V", &[level.into()]);
}

fn notify_text(env: &mut JNIEnv, obj: &JObject, text: &str) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(
            obj,
            "onTextTranscribed",
            "(Ljava/lang/String;)V",
            &[(&jtxt).into()],
        );
    }
}

fn notify_metrics(env: &mut JNIEnv, obj: &JObject, audio_ms: u64, compute_ms: u64) {
    let _ = env.call_method(
        obj,
        "onTranscriptionMetrics",
        "(JJI)V",
        &[
            JValue::Long(audio_ms as i64),
            JValue::Long(compute_ms as i64),
            JValue::Int(engine::active_thread_count()),
        ],
    );
}

pub fn init_session(env: JNIEnv, target: JObject, owner: u8) -> VoiceSessionState {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Off),
    );

    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let target_ref = env.new_global_ref(&target).expect("Failed to ref target");

    let state = VoiceSessionState {
        stream: None,
        audio_buffer: Arc::new(Mutex::new(CaptureBuffer::default())),
        jvm: vm_arc.clone(),
        target_ref: target_ref.clone(),
        session_active: Arc::new(AtomicBool::new(false)),
        capture_paused: Arc::new(AtomicBool::new(false)),
        owner,
        owns_session: Arc::new(AtomicBool::new(false)),
        inference_running: Arc::new(AtomicBool::new(false)),
        background: None,
    };

    // Load engine in background
    let vm_clone = vm_arc.clone();
    let target_ref_clone = target_ref.clone();

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_clone, &target_ref_clone);
    });

    state
}

/// Begin microphone capture. With `auto_stop` set, a monitor thread watches
/// for trailing silence after speech (or a no-speech timeout) and invokes the
/// Java-side `onAutoStop()` callback, which is expected to stop the recording
/// the same way a manual tap would.
pub fn start_recording(
    env: JNIEnv,
    state: &mut VoiceSessionState,
    auto_stop: bool,
    trailing_pause_ms: i32,
) {
    // Snapshot once for this session. Changing settings cannot stop an active
    // recording unexpectedly, and the audio callback performs no settings I/O.
    let delay = crate::interactive_endpointing::resolve_silence_ms(trailing_pause_ms);
    let _ = start_recording_impl(env, state, auto_stop, delay, None);
}

/// Start capture for the foreground service with cancellation fences around native setup.
pub fn start_recording_guarded(
    env: JNIEnv,
    state: &mut VoiceSessionState,
    generation: i64,
    attempt: i64,
) -> i32 {
    start_recording_impl(
        env,
        state,
        false,
        crate::interactive_endpointing::AUTO_STOP_SILENCE_MS,
        Some((generation, attempt)),
    )
}

fn start_recording_impl(
    mut env: JNIEnv,
    state: &mut VoiceSessionState,
    auto_stop: bool,
    trailing_pause_ms: u64,
    gate: Option<(i64, i64)>,
) -> i32 {
    if start_is_cancelled(&mut env, state, gate) {
        return 1;
    }
    if !claim_session_owner(state.owner) {
        notify_status(
            &mut env,
            state.target_ref.as_obj(),
            "Error: another transcription is already active.",
        );
        return 2;
    }
    state.owns_session.store(true, Ordering::SeqCst);
    state.inference_running.store(false, Ordering::SeqCst);
    let host = cpal::default_host();
    let _device = match host.default_input_device() {
        Some(d) => d,
        None => {
            notify_status(
                &mut env,
                state.target_ref.as_obj(),
                "Error: no microphone available. Check permissions.",
            );
            release_owned_session(state);
            return 2;
        }
    };

    state.audio_buffer.lock().unwrap().reset();
    let prepare_long_dictation = state.owner == OWNER_IME
        && crate::assets::files_dir(&mut env, state.target_ref.as_obj())
            .map(|dir| dir.join("background_predecode").is_file())
            .unwrap_or(false);
    let background = Arc::new(BackgroundTranscriber::start_with_capture(
        state.jvm.clone(),
        state.target_ref.clone(),
        prepare_long_dictation.then(|| state.audio_buffer.clone()),
    ));
    state.background = Some(background.clone());
    let buffer_clone = state.audio_buffer.clone();
    state.capture_paused.store(false, Ordering::SeqCst);
    let capture_paused = state.capture_paused.clone();

    // End any previous session's monitor, then arm a fresh flag.
    state.session_active.store(false, Ordering::SeqCst);
    let session_active = Arc::new(AtomicBool::new(true));
    state.session_active = session_active.clone();

    let capture_started = Instant::now();
    let endpoint = if auto_stop {
        Some(Arc::new(Endpointing {
            last_voice_ms: AtomicU64::new(0),
            speech_started: AtomicBool::new(false),
            audio_started: AtomicBool::new(false),
        }))
    } else {
        None
    };

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();
    let endpoint_cb = endpoint.clone();
    // Only the foreground service needs the native capture-health watchdog.
    // Avoid two contended mutex updates per audio callback for the ordinary
    // IME and popup paths.
    let health_callback_count = gate.map(|_| Arc::new(AtomicU64::new(0)));
    let health_callback_count_data = health_callback_count.clone();
    let health_nonzero_count = gate.map(|_| Arc::new(AtomicU64::new(0)));
    let health_nonzero_count_data = health_nonzero_count.clone();
    let error_jvm = state.jvm.clone();
    let error_target = state.target_ref.clone();
    let error_generation = gate.map(|(generation, _)| generation);
    let error_active = state.session_active.clone();
    let error_owns_session = state.owns_session.clone();
    let error_inference_running = state.inference_running.clone();
    let error_background = background.clone();
    let error_owner = state.owner;

    if start_is_cancelled(&mut env, state, gate) {
        state.session_active.store(false, Ordering::SeqCst);
        stop_background_worker(state);
        release_owned_session(state);
        return 1;
    }

    let mut endpointer = crate::endpoint::Endpointer::new();
    let mut last_level_sent = Instant::now();
    let mut audio_started = false;
    // Optional Bluetooth microphone: requested only for a start that is still
    // wanted, and held by the capture so every exit path releases it.
    let jvm_for_capture = state.jvm.clone();
    let target_for_capture = state.target_ref.clone();
    let stream = crate::managed_capture::ManagedCapture::open(
        &mut env,
        &jvm_for_capture,
        target_for_capture.as_obj(),
        move |data: &[f32]| {
            if let Some(callback_count) = &health_callback_count_data {
                callback_count.fetch_add(1, Ordering::Relaxed);
            }
            if capture_paused.load(Ordering::SeqCst) {
                return;
            }
            // Compute RMS and the capture-health signal in one pass.
            let mut sum = 0.0f32;
            let mut has_nonzero = false;
            for &x in data {
                sum += x * x;
                has_nonzero |= x.abs() > f32::EPSILON;
            }
            if has_nonzero {
                if !audio_started {
                    audio_started = true;
                    if let Some(ep) = &endpoint_cb {
                        ep.audio_started.store(true, Ordering::SeqCst);
                    }
                }
                if let Some(nonzero_count) = &health_nonzero_count_data {
                    nonzero_count.fetch_add(1, Ordering::Relaxed);
                }
            }
            let rms = (sum / (data.len().max(1) as f32)).sqrt();
            let level = (rms * 6.0).clamp(0.0, 1.0);
            endpointer.push(data);
            let is_speech = endpointer.is_voiced();

            // Do not buffer system-muted leading silence. Besides avoiding a
            // false empty transcription, this prevents needless background
            // inference and memory churn while the OS access prompt is open.
            if audio_started {
                buffer_clone.lock().unwrap().push(data);
            }

            if let Some(ep) = &endpoint_cb {
                if is_speech {
                    ep.last_voice_ms.store(
                        capture_started.elapsed().as_millis() as u64,
                        Ordering::Relaxed,
                    );
                    ep.speech_started.store(true, Ordering::SeqCst);
                }
            }

            // throttle updates
            if audio_started && last_level_sent.elapsed() >= Duration::from_millis(LEVEL_UPDATE_MS)
            {
                last_level_sent = Instant::now();

                if let Ok(mut env) = jvm.attach_current_thread() {
                    let obj = target_ref.as_obj();
                    notify_level(&mut env, obj, level);
                }
            }
        },
        move |error: String| {
            if let Some(generation) = error_generation {
                if error_active.swap(false, Ordering::SeqCst) {
                    if let Ok(mut callback_env) = error_jvm.attach_current_thread() {
                        let _ = callback_env.call_method(
                            error_target.as_obj(),
                            "onNativeCaptureError",
                            "(J)V",
                            &[JValue::Long(generation)],
                        );
                    }
                }
            } else {
                log::error!("Stream error: {}", error);
                error_active.store(false, Ordering::SeqCst);
                if let Some(completion) = error_background.cancel() {
                    error_inference_running.store(false, Ordering::SeqCst);
                    if error_owns_session.swap(false, Ordering::SeqCst) {
                        release_session_owner(error_owner);
                    }
                    std::thread::spawn(move || {
                        let _ = completion.recv();
                    });
                }
            }
        },
    );

    match stream {
        Ok(s) => {
            let bluetooth_watched = s.bluetooth_watched();
            let capture = SendStream(s);
            if start_is_cancelled(&mut env, state, gate) {
                state.session_active.store(false, Ordering::SeqCst);
                stop_background_worker(state);
                release_owned_session(state);
                return 1;
            }
            if let Err(e) = capture.0.play() {
                state.session_active.store(false, Ordering::SeqCst);
                stop_background_worker(state);
                release_owned_session(state);
                notify_status(
                    &mut env,
                    state.target_ref.as_obj(),
                    &format!("Error: failed to start microphone: {}", e),
                );
                return 2;
            }
            state.stream = Some(capture);
            notify_status(&mut env, state.target_ref.as_obj(), "Listening...");

            if let Some((generation, _)) = gate {
                let health_callback_count = health_callback_count
                    .expect("foreground capture health callback counter exists");
                let health_nonzero_count =
                    health_nonzero_count.expect("foreground capture health audio counter exists");
                let session_active = state.session_active.clone();
                let jvm = state.jvm.clone();
                let target_ref = state.target_ref.clone();
                std::thread::spawn(move || {
                    let mut callback_seen = 0;
                    let mut nonzero_seen = 0;
                    let mut callback_changed = Instant::now();
                    let mut nonzero_changed = Instant::now();
                    loop {
                        std::thread::sleep(Duration::from_millis(CAPTURE_HEALTH_MONITOR_MS));
                        if !session_active.load(Ordering::SeqCst) {
                            return;
                        }
                        if bluetooth_watched.load(Ordering::SeqCst) {
                            // The Bluetooth watchdog judges this stream and
                            // recovers it (a moment of zeros after a route
                            // switch is normal there); judge only what follows.
                            callback_changed = Instant::now();
                            nonzero_changed = Instant::now();
                            continue;
                        }
                        let callbacks = health_callback_count.load(Ordering::Relaxed);
                        if callbacks != callback_seen {
                            callback_seen = callbacks;
                            callback_changed = Instant::now();
                        }
                        let nonzero = health_nonzero_count.load(Ordering::Relaxed);
                        if nonzero != nonzero_seen {
                            nonzero_seen = nonzero;
                            nonzero_changed = Instant::now();
                        }
                        let callback_stalled = callback_changed.elapsed() >= Duration::from_secs(2);
                        let digital_zero =
                            has_two_seconds_of_digital_zero(nonzero_changed.elapsed());
                        if (callback_stalled || digital_zero)
                            && session_active.swap(false, Ordering::SeqCst)
                        {
                            if let Ok(mut callback_env) = jvm.attach_current_thread() {
                                let _ = callback_env.call_method(
                                    target_ref.as_obj(),
                                    "onNativeCaptureError",
                                    "(J)V",
                                    &[JValue::Long(generation)],
                                );
                            }
                            return;
                        }
                    }
                });
            }

            let jvm = state.jvm.clone();
            let target_ref = state.target_ref.clone();
            let capture_paused = state.capture_paused.clone();
            let monitor_interval = if endpoint.is_some() {
                AUTO_STOP_MONITOR_MS
            } else {
                MANUAL_MONITOR_MS
            };
            let predecode = prepare_long_dictation.then(|| background.clone());
            std::thread::spawn(move || {
                let mut no_speech_since = Instant::now();
                loop {
                    std::thread::sleep(Duration::from_millis(monitor_interval));
                    if !session_active.load(Ordering::SeqCst) {
                        return;
                    }
                    if let Some(background) = &predecode {
                        background.request_preparation();
                    }
                    let paused = capture_paused.load(Ordering::SeqCst);
                    let waiting_for_audio = endpoint
                        .as_ref()
                        .is_some_and(|ep| !ep.audio_started.load(Ordering::SeqCst));
                    if paused || waiting_for_audio {
                        // Pausing is user-controlled, so it must suspend the
                        // silence timer as well as audio buffering. The same
                        // applies while Android feeds digital zero with the
                        // device-wide microphone toggle disabled.
                        no_speech_since = Instant::now();
                        if let Some(ep) = &endpoint {
                            ep.last_voice_ms.store(
                                capture_started.elapsed().as_millis() as u64,
                                Ordering::Relaxed,
                            );
                        }
                    }
                    let speech = endpoint
                        .as_ref()
                        .is_some_and(|ep| ep.speech_started.load(Ordering::SeqCst));
                    let silence = endpoint
                        .as_ref()
                        .map(|ep| {
                            Duration::from_millis(
                                (capture_started.elapsed().as_millis() as u64)
                                    .saturating_sub(ep.last_voice_ms.load(Ordering::Relaxed)),
                            )
                        })
                        .unwrap_or_default();
                    if should_finish_interactive_session(
                        endpoint.is_some(),
                        paused,
                        !waiting_for_audio,
                        speech,
                        silence,
                        no_speech_since.elapsed(),
                        capture_started.elapsed(),
                        trailing_pause_ms,
                    ) {
                        // Claim the session so a simultaneous manual stop and
                        // this monitor can't both fire. Auto-stop always
                        // transcribes; it never discards captured speech.
                        if session_active.swap(false, Ordering::SeqCst) {
                            if let Ok(mut env) = jvm.attach_current_thread() {
                                let _ =
                                    env.call_method(target_ref.as_obj(), "onAutoStop", "()V", &[]);
                            }
                        }
                        return;
                    }
                }
            });
            0
        }
        Err(e) => {
            state.session_active.store(false, Ordering::SeqCst);
            stop_background_worker(state);
            release_owned_session(state);
            notify_status(
                &mut env,
                state.target_ref.as_obj(),
                &format!("Error: failed to open microphone: {}", e),
            );
            2
        }
    }
}

fn start_is_cancelled(
    env: &mut JNIEnv,
    state: &VoiceSessionState,
    gate: Option<(i64, i64)>,
) -> bool {
    let Some((generation, attempt)) = gate else {
        return false;
    };
    env.call_method(
        state.target_ref.as_obj(),
        "isStartCancelled",
        "(JJ)Z",
        &[JValue::Long(generation), JValue::Long(attempt)],
    )
    .and_then(|value| value.z())
    .unwrap_or(true)
}

pub fn end_recording_capture(state: &mut VoiceSessionState) -> bool {
    // Drop the stream to stop recording; end the auto-stop monitor if running.
    state.session_active.store(false, Ordering::SeqCst);
    state.capture_paused.store(false, Ordering::SeqCst);
    state.stream = None;

    state.audio_buffer.lock().unwrap().has_audio()
}

pub fn transcribe_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    let (tail, total_samples) = {
        let mut capture = state.audio_buffer.lock().unwrap();
        (capture.take_tail(), capture.total_samples())
    };

    // Guard against an empty buffer. Permission is checked by each Android
    // entry point before capture; an empty callback stream is not proof that
    // permission was denied.
    if total_samples == 0 {
        notify_status(
            &mut env,
            state.target_ref.as_obj(),
            "Error: no audio was captured. Try again.",
        );
        stop_background_worker(state);
        release_owned_session(state);
        return;
    }

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();
    let owner = state.owner;
    let owns_session = state.owns_session.clone();
    let inference_running = state.inference_running.clone();
    inference_running.store(true, Ordering::SeqCst);

    let Some(background) = state.background.take() else {
        notify_status(
            &mut env,
            target_ref.as_obj(),
            "Error: transcription worker unavailable",
        );
        inference_running.store(false, Ordering::SeqCst);
        release_owned_session(state);
        return;
    };
    let Some(completion) = background.finish(tail, total_samples) else {
        notify_status(
            &mut env,
            target_ref.as_obj(),
            "Error: transcription was already finalized",
        );
        inference_running.store(false, Ordering::SeqCst);
        release_owned_session(state);
        return;
    };

    notify_status(&mut env, target_ref.as_obj(), "Transcribing...");

    std::thread::spawn(move || {
        let mut env = match jvm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => {
                if owns_session.swap(false, Ordering::SeqCst) {
                    release_session_owner(owner);
                }
                inference_running.store(false, Ordering::SeqCst);
                return;
            }
        };
        let obj = target_ref.as_obj();

        match completion.recv() {
            Ok(Completion::Finished {
                text,
                audio_ms,
                compute_ms,
                pipeline,
            }) => {
                crate::background_transcription::notify_pipeline(&mut env, obj, &pipeline);
                notify_metrics(&mut env, obj, audio_ms, compute_ms);
                match text {
                    Ok(text) => {
                        notify_status(&mut env, obj, "Ready");
                        notify_text(&mut env, obj, &text);
                    }
                    Err(e) => notify_status(&mut env, obj, &format!("Error: {}", e)),
                }
            }
            Ok(Completion::Cancelled) => {}
            Err(_) => notify_status(&mut env, obj, "Error: transcription worker stopped"),
        }
        inference_running.store(false, Ordering::SeqCst);
        if owns_session.swap(false, Ordering::SeqCst) {
            release_session_owner(owner);
        }
    });
}

pub fn stop_recording(env: JNIEnv, state: &mut VoiceSessionState) {
    end_recording_capture(state);
    transcribe_recording(env, state);
}

/// Pause or resume without discarding samples already recorded. Buffering is
/// always gated; supported Android backends also suspend the microphone stream.
pub fn set_recording_paused(state: &mut VoiceSessionState, paused: bool) -> bool {
    if state.stream.is_none() || !state.session_active.load(Ordering::SeqCst) {
        return false;
    }
    let stream = state.stream.as_ref().unwrap();
    if paused {
        // Gate the callback first, then ask the native backend to suspend the
        // AudioRecord stream. If a vendor backend rejects pause, capture stays
        // reliable and the callback remains gated; supported devices stop the
        // microphone hardware until Resume.
        state.capture_paused.store(true, Ordering::SeqCst);
        let _ = stream.0.pause();
        true
    } else if stream.0.play().is_ok() {
        state.capture_paused.store(false, Ordering::SeqCst);
        true
    } else {
        false
    }
}

pub fn cancel_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    cleanup_session(state);
    notify_status(&mut env, state.target_ref.as_obj(), "Canceled");
}

pub fn cleanup_session(state: &mut VoiceSessionState) {
    state.session_active.store(false, Ordering::SeqCst);
    state.capture_paused.store(false, Ordering::SeqCst);
    state.stream = None;
    state.audio_buffer.lock().unwrap().reset();
    if let Some(background) = state.background.take() {
        state.inference_running.store(false, Ordering::SeqCst);
        // A cancelled capture has no pending result. Let a replacement editor
        // claim capture ownership immediately; an in-flight native run remains
        // safely serialized by the engine mutex while it winds down.
        release_owned_session(state);
        if let Some(completion) = background.cancel() {
            std::thread::spawn(move || {
                let _ = completion.recv();
            });
        }
    } else if !state.inference_running.load(Ordering::SeqCst) {
        release_owned_session(state);
    }
}

fn release_owned_session(state: &VoiceSessionState) {
    if state.owns_session.swap(false, Ordering::SeqCst) {
        release_session_owner(state.owner);
    }
}

fn stop_background_worker(state: &mut VoiceSessionState) {
    if let Some(background) = state.background.take() {
        if let Some(completion) = background.cancel() {
            std::thread::spawn(move || {
                let _ = completion.recv();
            });
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    static OWNER_TEST_LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn digital_zero_requires_a_consecutive_two_second_window() {
        assert!(!has_two_seconds_of_digital_zero(Duration::from_millis(
            1_999
        )));
        assert!(has_two_seconds_of_digital_zero(Duration::from_secs(2)));
        assert!(has_two_seconds_of_digital_zero(Duration::from_secs(3)));
    }

    #[test]
    fn process_owner_rejects_a_competing_capture_until_release() {
        let _guard = OWNER_TEST_LOCK.lock().unwrap();
        const TEST_OWNER_A: u8 = 250;
        const TEST_OWNER_B: u8 = 251;
        assert!(claim_session_owner(TEST_OWNER_A));
        assert!(!claim_session_owner(TEST_OWNER_B));
        release_session_owner(TEST_OWNER_A);
        assert!(claim_session_owner(TEST_OWNER_B));
        release_session_owner(TEST_OWNER_B);
    }
}
