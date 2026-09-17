use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use jni::objects::{GlobalRef, JObject, JValue};
use jni::JNIEnv;

use crate::engine;

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
/// Absolute smoothed level (0..1) that must be exceeded to count as speech.
const MIN_SPEECH_LEVEL: f32 = 0.12;
/// How far above the running noise floor a level must be to count as speech.
const SPEECH_MARGIN: f32 = 0.08;
/// Trailing silence after speech that triggers auto-stop.
const AUTO_STOP_SILENCE_MS: u64 = 2000;
/// If no speech is ever detected, auto-stop after this long.
const AUTO_STOP_NO_SPEECH_MS: u64 = 8000;
const DIGITAL_ZERO_TIMEOUT: Duration = Duration::from_secs(2);

fn has_two_seconds_of_digital_zero(since_last_nonzero: Duration) -> bool {
    since_last_nonzero >= DIGITAL_ZERO_TIMEOUT
}

pub struct SendStream(#[allow(dead_code)] pub cpal::Stream);
unsafe impl Send for SendStream {}
unsafe impl Sync for SendStream {}

/// Speech/silence tracking shared between the audio callback and the
/// auto-stop monitor thread.
struct Endpointing {
    last_voice: Mutex<Instant>,
    noise_floor: Mutex<f32>,
    speech_started: AtomicBool,
}

pub struct VoiceSessionState {
    pub stream: Option<SendStream>,
    pub audio_buffer: Arc<Mutex<Vec<f32>>>,
    pub jvm: Arc<jni::JavaVM>,
    pub target_ref: GlobalRef,
    pub last_level_sent: Arc<Mutex<std::time::Instant>>,
    /// True while the current recording runs; flipped off on stop/cancel so
    /// the auto-stop monitor (if any) exits.
    pub session_active: Arc<AtomicBool>,
    /// Drops callback samples while the IME is paused. Android's native audio
    /// backend does not reliably implement Stream::pause(), so capture stays
    /// open while the buffer and level meter are gated here.
    capture_paused: Arc<AtomicBool>,
    owner: u8,
    owns_session: Arc<AtomicBool>,
    inference_running: Arc<AtomicBool>,
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

pub fn init_session(env: JNIEnv, target: JObject, owner: u8) -> VoiceSessionState {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Off),
    );

    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let target_ref = env.new_global_ref(&target).expect("Failed to ref target");

    let state = VoiceSessionState {
        stream: None,
        audio_buffer: Arc::new(Mutex::new(Vec::new())),
        jvm: vm_arc.clone(),
        target_ref: target_ref.clone(),
        last_level_sent: Arc::new(Mutex::new(std::time::Instant::now())),
        session_active: Arc::new(AtomicBool::new(false)),
        capture_paused: Arc::new(AtomicBool::new(false)),
        owner,
        owns_session: Arc::new(AtomicBool::new(false)),
        inference_running: Arc::new(AtomicBool::new(false)),
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
pub fn start_recording(env: JNIEnv, state: &mut VoiceSessionState, auto_stop: bool) {
    let _ = start_recording_impl(env, state, auto_stop, None);
}

/// Start capture for the foreground service with cancellation fences around native setup.
pub fn start_recording_guarded(
    env: JNIEnv,
    state: &mut VoiceSessionState,
    generation: i64,
    attempt: i64,
) -> i32 {
    start_recording_impl(env, state, false, Some((generation, attempt)))
}

fn start_recording_impl(
    mut env: JNIEnv,
    state: &mut VoiceSessionState,
    auto_stop: bool,
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
    let device = match host.default_input_device() {
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

    let config = cpal::StreamConfig {
        channels: 1,
        sample_rate: cpal::SampleRate(16000),
        buffer_size: cpal::BufferSize::Default,
    };

    state.audio_buffer.lock().unwrap().clear();
    let buffer_clone = state.audio_buffer.clone();
    state.capture_paused.store(false, Ordering::SeqCst);
    let capture_paused = state.capture_paused.clone();

    // End any previous session's monitor, then arm a fresh flag.
    state.session_active.store(false, Ordering::SeqCst);
    let session_active = Arc::new(AtomicBool::new(true));
    state.session_active = session_active.clone();

    let endpoint = if auto_stop {
        Some(Arc::new(Endpointing {
            last_voice: Mutex::new(Instant::now()),
            noise_floor: Mutex::new(0.0),
            speech_started: AtomicBool::new(false),
        }))
    } else {
        None
    };

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();
    let last_sent = state.last_level_sent.clone();
    let endpoint_cb = endpoint.clone();
    let health_last_callback = Arc::new(Mutex::new(Instant::now()));
    let health_last_callback_data = health_last_callback.clone();
    let health_last_nonzero = Arc::new(Mutex::new(Instant::now()));
    let health_last_nonzero_data = health_last_nonzero.clone();
    let error_jvm = state.jvm.clone();
    let error_target = state.target_ref.clone();
    let error_generation = gate.map(|(generation, _)| generation);
    let error_active = state.session_active.clone();
    let error_owns_session = state.owns_session.clone();
    let error_owner = state.owner;

    if start_is_cancelled(&mut env, state, gate) {
        state.session_active.store(false, Ordering::SeqCst);
        release_owned_session(state);
        return 1;
    }

    let stream = device.build_input_stream(
        &config,
        move |data: &[f32], _: &_| {
            *health_last_callback_data.lock().unwrap() = Instant::now();
            if data.iter().any(|sample| sample.abs() > f32::EPSILON) {
                *health_last_nonzero_data.lock().unwrap() = Instant::now();
            }
            if capture_paused.load(Ordering::SeqCst) {
                return;
            }
            buffer_clone.lock().unwrap().extend_from_slice(data);

            // compute RMS
            let mut sum = 0.0f32;
            for &x in data {
                sum += x * x;
            }
            let rms = (sum / (data.len().max(1) as f32)).sqrt();
            let level = (rms * 6.0).clamp(0.0, 1.0);

            if let Some(ep) = &endpoint_cb {
                let floor = *ep.noise_floor.lock().unwrap();
                let is_speech = level > MIN_SPEECH_LEVEL && level > floor + SPEECH_MARGIN;
                if is_speech {
                    *ep.last_voice.lock().unwrap() = Instant::now();
                    ep.speech_started.store(true, Ordering::SeqCst);
                } else {
                    // Slowly adapt the noise floor while no speech is present.
                    let mut nf = ep.noise_floor.lock().unwrap();
                    *nf = *nf * 0.95 + level * 0.05;
                }
            }

            // throttle updates
            let mut last = last_sent.lock().unwrap();
            if last.elapsed() >= std::time::Duration::from_millis(50) {
                *last = std::time::Instant::now();

                if let Ok(mut env) = jvm.attach_current_thread() {
                    let obj = target_ref.as_obj();
                    notify_level(&mut env, obj, level);
                }
            }
        },
        move |error| {
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
                if error_owns_session.swap(false, Ordering::SeqCst) {
                    release_session_owner(error_owner);
                }
            }
        },
        None,
    );

    match stream {
        Ok(s) => {
            if start_is_cancelled(&mut env, state, gate) {
                state.session_active.store(false, Ordering::SeqCst);
                release_owned_session(state);
                return 1;
            }
            if let Err(e) = s.play() {
                state.session_active.store(false, Ordering::SeqCst);
                release_owned_session(state);
                notify_status(
                    &mut env,
                    state.target_ref.as_obj(),
                    &format!("Error: failed to start microphone: {}", e),
                );
                return 2;
            }
            state.stream = Some(SendStream(s));
            notify_status(&mut env, state.target_ref.as_obj(), "Listening...");

            if let Some((generation, _)) = gate {
                let session_active = state.session_active.clone();
                let jvm = state.jvm.clone();
                let target_ref = state.target_ref.clone();
                std::thread::spawn(move || loop {
                    std::thread::sleep(Duration::from_millis(250));
                    if !session_active.load(Ordering::SeqCst) {
                        return;
                    }
                    let callback_stalled =
                        health_last_callback.lock().unwrap().elapsed() >= Duration::from_secs(2);
                    let digital_zero = has_two_seconds_of_digital_zero(
                        health_last_nonzero.lock().unwrap().elapsed(),
                    );
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
                });
            }

            if let Some(ep) = endpoint {
                let jvm = state.jvm.clone();
                let target_ref = state.target_ref.clone();
                let started_at = Instant::now();
                std::thread::spawn(move || loop {
                    std::thread::sleep(Duration::from_millis(100));
                    if !session_active.load(Ordering::SeqCst) {
                        return;
                    }
                    let speech = ep.speech_started.load(Ordering::SeqCst);
                    let silence = ep.last_voice.lock().unwrap().elapsed();
                    let done = (speech && silence >= Duration::from_millis(AUTO_STOP_SILENCE_MS))
                        || (!speech
                            && started_at.elapsed()
                                >= Duration::from_millis(AUTO_STOP_NO_SPEECH_MS));
                    if done {
                        // Claim the session so a simultaneous manual stop and
                        // this monitor can't both fire.
                        if session_active.swap(false, Ordering::SeqCst) {
                            if let Ok(mut env) = jvm.attach_current_thread() {
                                let _ =
                                    env.call_method(target_ref.as_obj(), "onAutoStop", "()V", &[]);
                            }
                        }
                        return;
                    }
                });
            }
            0
        }
        Err(e) => {
            state.session_active.store(false, Ordering::SeqCst);
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

    !state.audio_buffer.lock().unwrap().is_empty()
}

pub fn transcribe_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    // Move the captured samples into inference instead of duplicating the
    // entire recording. This also clears the process-held capture buffer at
    // the capture/inference boundary.
    let buffer = std::mem::take(&mut *state.audio_buffer.lock().unwrap());

    // Guard against empty buffer (mic permission denied, instant stop, etc.)
    if buffer.is_empty() {
        notify_status(
            &mut env,
            state.target_ref.as_obj(),
            "Error: no audio recorded. Check microphone permissions.",
        );
        release_owned_session(state);
        return;
    }

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();
    let owner = state.owner;
    let owns_session = state.owns_session.clone();
    let inference_running = state.inference_running.clone();
    inference_running.store(true, Ordering::SeqCst);

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

        // Wait for engine if somehow still loading
        if engine::get_engine().is_none() {
            if let Err(_) = engine::ensure_loaded(&mut env, obj) {
                if owns_session.swap(false, Ordering::SeqCst) {
                    release_session_owner(owner);
                }
                inference_running.store(false, Ordering::SeqCst);
                return;
            }
        }

        if let Some(eng_arc) = engine::get_engine() {
            let res = engine::transcribe_shared(&eng_arc, buffer);

            match res {
                Ok(text) => {
                    notify_status(&mut env, obj, "Ready");
                    notify_text(&mut env, obj, &text);
                }
                Err(e) => notify_status(&mut env, obj, &format!("Error: {}", e)),
            }
        } else {
            notify_status(&mut env, obj, "Error: model not loaded");
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

/// Pause or resume buffering without discarding samples already recorded.
/// Keeping the Android stream open avoids backend-specific pause failures;
/// callback samples and level updates are ignored while this flag is set.
pub fn set_recording_paused(state: &mut VoiceSessionState, paused: bool) -> bool {
    if state.stream.is_none() || !state.session_active.load(Ordering::SeqCst) {
        return false;
    }
    state.capture_paused.store(paused, Ordering::SeqCst);
    true
}

pub fn cancel_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    cleanup_session(state);
    notify_status(&mut env, state.target_ref.as_obj(), "Canceled");
}

pub fn cleanup_session(state: &mut VoiceSessionState) {
    state.session_active.store(false, Ordering::SeqCst);
    state.capture_paused.store(false, Ordering::SeqCst);
    state.stream = None;
    state.audio_buffer.lock().unwrap().clear();
    if !state.inference_running.load(Ordering::SeqCst) {
        release_owned_session(state);
    }
}

fn release_owned_session(state: &VoiceSessionState) {
    if state.owns_session.swap(false, Ordering::SeqCst) {
        release_session_owner(state.owner);
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
