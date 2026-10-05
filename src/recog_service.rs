//! Native backend for `VoiceRecognitionService`, the `android.speech.RecognitionService`
//! implementation that lets *other* keyboards/apps (SwiftKey, Gboard, …) use this app
//! as their offline speech-to-text provider via the system `SpeechRecognizer` API.
//!
//! Unlike the IME / `RecognizeActivity` surfaces (which have their own UI and a manual
//! "tap to stop" control via `voice_session`), a `RecognitionService` has no UI of its
//! own: the calling keyboard expects *us* to decide when the user has finished speaking.
//! So this module adds trailing-silence endpointing on top of the same `engine` model,
//! and finalises automatically (it also honours an explicit `stopListening`/`cancel`).

use std::fs::File;
use std::io::{self, ErrorKind};
use std::os::fd::FromRawFd;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use jni::objects::{GlobalRef, JClass, JObject, JValue};
use jni::sys::jint;
use jni::JNIEnv;
use once_cell::sync::Lazy;

use crate::background_chunking::CaptureBuffer;
use crate::background_transcription::{BackgroundTranscriber, Completion};
use crate::caller_audio_input::{self, InputEnd, Readiness, SourceState};
use crate::engine;
use crate::voice_session::{
    claim_session_owner, release_session_owner, SendStream, OWNER_RECOGNITION_SERVICE,
};

// --- Endpointing / VAD tuning -------------------------------------------------
// These are deliberately simple heuristics on the smoothed mic level. Mic gain
// varies a lot between devices, so they may need tuning; finalisation always
// transcribes whatever was captured, so a mis-tuned threshold only affects the
// auto-stop *timing*, never whether text is returned.
//
/// Absolute smoothed level (0..1) that must be exceeded to count as speech.
const MIN_SPEECH_LEVEL: f32 = 0.12;
/// How far above the running noise floor a level must be to count as speech.
const SPEECH_MARGIN: f32 = 0.08;
/// Trailing silence after speech that triggers auto-finalisation.
const SILENCE_MS: u64 = 1500;
/// If no speech is ever detected, finalise after this long anyway.
const NO_SPEECH_TIMEOUT_MS: u64 = 7000;
/// Hard cap on a single utterance (the engine internally chunks long audio).
const MAX_SESSION_MS: u64 = 60000;
/// Throttle interval for `rmsChanged` UI callbacks.
const LEVEL_UPDATE_MS: u64 = 100;
const ENDPOINT_MONITOR_MS: u64 = 250;

// --- Caller-supplied audio (RecognizerIntent.EXTRA_AUDIO_SOURCE) --------------
/// Rate the model runs at; caller audio is resampled to it.
const TARGET_SAMPLE_RATE: u32 = 16000;

// Mirror of the android.media.AudioFormat encodings a caller may name.
const ENCODING_PCM_16BIT: i32 = 2;
const ENCODING_PCM_8BIT: i32 = 3;
const ENCODING_PCM_FLOAT: i32 = 4;

// Mirror of android.speech.SpeechRecognizer error codes we report.
const ERROR_AUDIO: i32 = 3;
const ERROR_SERVER: i32 = 4;
const ERROR_NO_MATCH: i32 = 7;

/// State shared between the audio callback, the endpoint-monitor thread and the
/// finaliser. Deliberately does NOT hold the cpal stream, to avoid an Arc cycle
/// (the stream's callback holds an `Arc<Endpoint>`).
struct Endpoint {
    audio_buffer: Mutex<CaptureBuffer>,
    background: Arc<BackgroundTranscriber>,
    /// Milliseconds since `started_at`; this avoids timing mutexes in the
    /// latency-sensitive audio callback.
    last_voice_ms: AtomicU64,
    speech_started: AtomicBool,
    finalized: AtomicBool,
    cancelled: AtomicBool,
    inference_running: AtomicBool,
    /// Caller stop drains already-readable bytes; it must not finalize on the Java thread.
    stop_after_ms: AtomicU64,
    started_at: Instant,
    /// A caller streaming its own audio owns the endpoint: the session runs until the audio is
    /// closed or `stopListening` arrives, so trailing-silence auto-stop must not cut it short.
    caller_endpointed: bool,
    jvm: Arc<jni::JavaVM>,
    target: GlobalRef,
}

/// Analysis state has exactly one producer per session: either the microphone
/// callback or the caller-audio reader. It therefore stays local and lock-free.
struct AudioAnalyzer {
    noise_floor: f32,
    last_level_sent: Instant,
}

impl AudioAnalyzer {
    fn new() -> Self {
        Self {
            noise_floor: 0.0,
            last_level_sent: Instant::now(),
        }
    }
}

struct Session {
    shared: Arc<Endpoint>,
    stream: Arc<Mutex<Option<SendStream>>>,
}

static SESSION: Lazy<Mutex<Option<Session>>> = Lazy::new(|| Mutex::new(None));

// --- JNI callbacks into VoiceRecognitionService -------------------------------

fn call_void(env: &mut JNIEnv, obj: &JObject, method: &str) {
    let _ = env.call_method(obj, method, "()V", &[]);
}

fn call_rms(env: &mut JNIEnv, obj: &JObject, rms_db: f32) {
    let _ = env.call_method(obj, "onRmsChanged", "(F)V", &[rms_db.into()]);
}

fn call_error(env: &mut JNIEnv, obj: &JObject, code: i32) {
    let _ = env.call_method(obj, "onError", "(I)V", &[code.into()]);
}

fn call_outcome(env: &mut JNIEnv, obj: &JObject, reason: i32, audio_ms: u64) {
    let result = env.call_method(
        obj,
        "onRecognitionOutcome",
        "(IJ)V",
        &[JValue::Int(reason), JValue::Long(audio_ms as i64)],
    );
    if result.is_err() || env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
    }
}

fn call_results(env: &mut JNIEnv, obj: &JObject, text: &str) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(obj, "onResults", "(Ljava/lang/String;)V", &[(&jtxt).into()]);
    }
}

fn call_metrics(env: &mut JNIEnv, obj: &JObject, audio_ms: u64, compute_ms: u64) {
    let result = env.call_method(
        obj,
        "onTranscriptionMetrics",
        "(JJI)V",
        &[
            JValue::Long(audio_ms as i64),
            JValue::Long(compute_ms as i64),
            JValue::Int(engine::active_thread_count()),
        ],
    );
    if result.is_err() || env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
    }
}

// --- JNI entry points ---------------------------------------------------------

/// Called from `onCreate`. Warms up the model in the background so the first
/// recognition after a cold bind is as fast as possible.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Off),
    );

    let jvm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let target_ref = match env.new_global_ref(&service) {
        Ok(r) => r,
        Err(_) => return,
    };

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&jvm, &target_ref);
    });
}

/// Called from `onStartListening`. Begins microphone capture and arms the
/// silence-based endpoint monitor.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_startListening(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    let jvm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let target = match env.new_global_ref(&service) {
        Ok(r) => r,
        Err(_) => return,
    };

    let (shared, stream_holder) = match begin_session(&jvm, target, false) {
        Some(session) => session,
        None => return,
    };

    // Open the microphone (16 kHz mono, matching the model + voice_session).
    let host = cpal::default_host();
    let device = match host.default_input_device() {
        Some(d) => d,
        None => {
            let mut env2 = jvm.attach_current_thread().unwrap();
            call_error(&mut env2, shared.target.as_obj(), ERROR_AUDIO);
            clear_session(&shared);
            return;
        }
    };
    let config = cpal::StreamConfig {
        channels: 1,
        sample_rate: cpal::SampleRate(16000),
        buffer_size: cpal::BufferSize::Default,
    };

    let cb_shared = shared.clone();
    let mut analyzer = AudioAnalyzer::new();
    let stream = device.build_input_stream(
        &config,
        move |data: &[f32], _: &_| audio_callback(&cb_shared, data, &mut analyzer),
        |e| log::error!("RecognitionService stream error: {}", e),
        None,
    );

    match stream {
        Ok(s) => {
            if shared.cancelled.load(Ordering::SeqCst) {
                return;
            }
            if let Err(e) = s.play() {
                log::error!("Failed to start microphone: {}", e);
                let mut env2 = jvm.attach_current_thread().unwrap();
                call_error(&mut env2, shared.target.as_obj(), ERROR_AUDIO);
                clear_session(&shared);
                return;
            }
            if shared.cancelled.load(Ordering::SeqCst) {
                drop(s);
                return;
            }
            *stream_holder.lock().unwrap() = Some(SendStream(s));
        }
        Err(e) => {
            log::error!("Failed to open microphone: {}", e);
            let mut env2 = jvm.attach_current_thread().unwrap();
            call_error(&mut env2, shared.target.as_obj(), ERROR_AUDIO);
            clear_session(&shared);
            return;
        }
    }

    // Endpoint monitor.
    let mon_shared = shared.clone();
    let mon_stream = stream_holder.clone();
    std::thread::spawn(move || endpoint_monitor(mon_shared, mon_stream));
}

/// Called from `onStartListening` when the caller passed
/// `RecognizerIntent.EXTRA_AUDIO_SOURCE`. Transcribes the audio arriving on `fd` instead of
/// opening the microphone, which is what lets an app that is not the system-selected recognizer's
/// owner use this engine at all — see the Java side for why the microphone path cannot serve it.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_startListeningFromAudioSource(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
    fd: jint,
    sample_rate: jint,
    channel_count: jint,
    encoding: jint,
) {
    let jvm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let target = match env.new_global_ref(&service) {
        Ok(r) => r,
        Err(_) => return,
    };

    let format = match SourceFormat::new(sample_rate, channel_count, encoding) {
        Some(format) => format,
        None => {
            log::error!(
                "unsupported audio source: {} Hz, {} channels, encoding {}",
                sample_rate,
                channel_count,
                encoding
            );
            drop(File::from_raw_fd(fd));
            if let Ok(mut env2) = jvm.attach_current_thread() {
                call_error(&mut env2, target.as_obj(), ERROR_AUDIO);
            }
            return;
        }
    };

    let (shared, stream_holder) = match begin_session(&jvm, target, true) {
        Some(session) => session,
        None => {
            drop(File::from_raw_fd(fd));
            return;
        }
    };

    let reader_shared = shared.clone();
    let reader_stream = stream_holder.clone();
    std::thread::spawn(move || read_caller_audio(reader_shared, reader_stream, fd, format));

    let mon_shared = shared.clone();
    let mon_stream = stream_holder.clone();
    std::thread::spawn(move || endpoint_monitor(mon_shared, mon_stream));
}

/// Called from `onStopListening`: the keyboard asked us to finish now. Finalise
/// with whatever we've captured so far.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_stopListening(
    _env: JNIEnv,
    _class: JClass,
) {
    let session = SESSION
        .lock()
        .unwrap()
        .as_ref()
        .map(|s| (s.shared.clone(), s.stream.clone()));
    if let Some((shared, stream)) = session {
        if shared.caller_endpointed {
            // Only the reader may finalize supplied PCM. Drain buffered bytes,
            // bounded to 500 ms total even if the caller continues writing.
            let deadline = (shared.started_at.elapsed().as_millis() as u64).saturating_add(500);
            let _ = shared.stop_after_ms.compare_exchange(
                u64::MAX,
                deadline,
                Ordering::SeqCst,
                Ordering::SeqCst,
            );
            return;
        }
        std::thread::spawn(move || finalize(shared, stream));
    }
}

/// Called from `onCancel`: discard everything, return nothing.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_cancelNative(
    _env: JNIEnv,
    _class: JClass,
) {
    let session = SESSION
        .lock()
        .unwrap()
        .as_ref()
        .map(|session| (session.shared.clone(), session.stream.clone()));
    let Some((shared, stream)) = session else {
        return;
    };
    shared.cancelled.store(true, Ordering::SeqCst);
    if shared
        .finalized
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        // The caller may disappear just after EOF moved the request into
        // finalization. The cancellation flag above suppresses that old
        // result; detach its process-wide ownership as well so a replacement
        // request does not see a false ERROR_AUDIO while native inference
        // finishes behind the engine mutex.
        clear_session(&shared);
        return;
    }
    shared.inference_running.store(true, Ordering::SeqCst);
    *stream.lock().unwrap() = None;
    let completion = shared.background.cancel();
    // Cancellation has no result to protect. Release capture ownership now so
    // an app/editor switch can start its replacement request immediately. A
    // non-interruptible native run may still wind down, but model access stays
    // serialized by the engine mutex and must not masquerade as an audio or
    // permission failure for the next caller.
    clear_session(&shared);
    if let Some(completion) = completion {
        std::thread::spawn(move || {
            let _ = completion.recv();
            shared.inference_running.store(false, Ordering::SeqCst);
        });
    } else {
        shared.inference_running.store(false, Ordering::SeqCst);
    }
}

/// Called from `onDestroy`.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_VoiceRecognitionService_destroyNative(
    env: JNIEnv,
    class: JClass,
) {
    Java_dev_notune_transcribe_VoiceRecognitionService_cancelNative(env, class);
}

// --- Session lifecycle --------------------------------------------------------

/// Claims the sole process-wide capture/model owner and tells the caller we are ready.
/// Returns `None` when another surface owns it or the JVM cannot be attached.
fn begin_session(
    jvm: &Arc<jni::JavaVM>,
    target: GlobalRef,
    caller_endpointed: bool,
) -> Option<(Arc<Endpoint>, Arc<Mutex<Option<SendStream>>>)> {
    // A second wrapper start must never replace the process-global owner.
    if SESSION.lock().unwrap().is_some() {
        if let Ok(mut env) = jvm.attach_current_thread() {
            call_error(&mut env, target.as_obj(), ERROR_AUDIO);
        }
        return None;
    }

    if !claim_session_owner(OWNER_RECOGNITION_SERVICE) {
        if let Ok(mut env) = jvm.attach_current_thread() {
            call_error(&mut env, target.as_obj(), ERROR_AUDIO);
        }
        return None;
    }

    let now = Instant::now();
    let background = Arc::new(BackgroundTranscriber::start(jvm.clone(), target.clone()));
    let shared = Arc::new(Endpoint {
        audio_buffer: Mutex::new(CaptureBuffer::default()),
        background,
        last_voice_ms: AtomicU64::new(0),
        speech_started: AtomicBool::new(false),
        finalized: AtomicBool::new(false),
        cancelled: AtomicBool::new(false),
        inference_running: AtomicBool::new(false),
        stop_after_ms: AtomicU64::new(u64::MAX),
        started_at: now,
        caller_endpointed,
        jvm: jvm.clone(),
        target,
    });

    let mut env = match jvm.attach_current_thread() {
        Ok(env) => env,
        Err(_) => {
            release_session_owner(OWNER_RECOGNITION_SERVICE);
            return None;
        }
    };
    let stream = Arc::new(Mutex::new(None));
    *SESSION.lock().unwrap() = Some(Session {
        shared: shared.clone(),
        stream: stream.clone(),
    });

    // Tell the caller we're ready to receive speech.
    call_void(&mut env, shared.target.as_obj(), "onReadyForSpeech");

    Some((shared, stream))
}

// --- Caller-supplied audio ----------------------------------------------------

/// The PCM layout a caller declared for `EXTRA_AUDIO_SOURCE`.
struct SourceFormat {
    sample_rate: u32,
    channels: usize,
    bytes_per_sample: usize,
    encoding: i32,
}

impl SourceFormat {
    fn new(sample_rate: i32, channels: i32, encoding: i32) -> Option<Self> {
        let bytes_per_sample = match encoding {
            ENCODING_PCM_8BIT => 1,
            ENCODING_PCM_16BIT => 2,
            ENCODING_PCM_FLOAT => 4,
            _ => return None,
        };
        if !(4000..=192_000).contains(&sample_rate) || !(1..=8).contains(&channels) {
            return None;
        }
        Some(Self {
            sample_rate: sample_rate as u32,
            channels: channels as usize,
            bytes_per_sample,
            encoding,
        })
    }

    fn frame_bytes(&self) -> usize {
        self.bytes_per_sample * self.channels
    }
}

/// Feeds the endpoint from the caller's descriptor until the audio is closed, the session is
/// cancelled, or reading fails. Closing the descriptor ends the utterance, the same end of audio
/// the microphone path gets from its own recorder stopping.
fn read_caller_audio(
    shared: Arc<Endpoint>,
    stream: Arc<Mutex<Option<SendStream>>>,
    fd: i32,
    format: SourceFormat,
) {
    // SAFETY: the descriptor was detached from the caller's ParcelFileDescriptor, so this File is
    // its sole owner and closes it on drop.
    let source = unsafe { File::from_raw_fd(fd) };
    let mut resampler = Resampler::new(format.sample_rate, TARGET_SAMPLE_RATE);
    let mut carry: Vec<u8> = Vec::new();
    let mut mono: Vec<f32> = Vec::new();
    let mut frames: Vec<f32> = Vec::new();
    let mut analyzer = AudioAnalyzer::new();

    let end = caller_audio_input::drain(
        source,
        || wait_readable(fd, &shared),
        |bytes| {
            carry.extend_from_slice(bytes);
            mono.clear();
            drain_mono_frames(&mut carry, &format, &mut mono);
            frames.clear();
            resampler.push(&mono, &mut frames);
            if !frames.is_empty() {
                audio_callback(&shared, &frames, &mut analyzer);
            }
        },
    );
    match end {
        Ok(InputEnd::Cancelled) => {}
        Ok(InputEnd::Finished) if carry.is_empty() => finalize(shared, stream),
        _ => fail_caller_audio(shared, stream),
    }
}

/// A malformed or failed source must not turn a partial recording into success.
fn fail_caller_audio(shared: Arc<Endpoint>, stream: Arc<Mutex<Option<SendStream>>>) {
    if shared
        .finalized
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        return;
    }
    *stream.lock().unwrap() = None;
    let completion = shared.background.cancel();
    if !shared.cancelled.load(Ordering::SeqCst) {
        if let Ok(mut env) = shared.jvm.attach_current_thread() {
            let audio_ms = shared
                .audio_buffer
                .lock()
                .unwrap()
                .total_samples()
                .saturating_mul(1000)
                / 16000;
            call_outcome(&mut env, shared.target.as_obj(), 3, audio_ms);
            call_error(&mut env, shared.target.as_obj(), ERROR_AUDIO);
        }
    }
    clear_session(&shared);
    if let Some(completion) = completion {
        let _ = completion.recv();
    }
}

/// Stop drains currently readable data without waiting for an unclosed writer.
/// Cancellation, safety limits, and poll errors have distinct outcomes.
fn wait_readable(fd: i32, shared: &Arc<Endpoint>) -> io::Result<Readiness> {
    caller_audio_input::wait_ready(
        || SourceState {
            cancelled: shared.cancelled.load(Ordering::SeqCst),
            finalized: shared.finalized.load(Ordering::SeqCst),
            elapsed_ms: shared.started_at.elapsed().as_millis() as u64,
            stop_after_ms: shared.stop_after_ms.load(Ordering::SeqCst),
        },
        |timeout| {
            let mut poll_fd = libc::pollfd {
                fd,
                events: libc::POLLIN,
                revents: 0,
            };
            // SAFETY: initialized pollfd naming the descriptor owned by this reader.
            let ready = unsafe { libc::poll(&mut poll_fd, 1, timeout) };
            if ready < 0 {
                return Err(io::Error::last_os_error());
            }
            if poll_fd.revents & (libc::POLLERR | libc::POLLNVAL) != 0 {
                return Err(io::Error::from(ErrorKind::BrokenPipe));
            }
            // POLLHUP drains remaining bytes before read() reports EOF.
            Ok(ready > 0)
        },
    )
}

/// Decodes every whole frame buffered in `carry` into mono samples, leaving a partial frame behind.
fn drain_mono_frames(carry: &mut Vec<u8>, format: &SourceFormat, out: &mut Vec<f32>) {
    let frame_bytes = format.frame_bytes();
    let complete = carry.len() / frame_bytes;
    if complete == 0 {
        return;
    }

    for frame in carry[..complete * frame_bytes].chunks_exact(frame_bytes) {
        let mut sum = 0.0f32;
        for sample in frame.chunks_exact(format.bytes_per_sample) {
            sum += decode_sample(sample, format.encoding);
        }
        out.push(sum / format.channels as f32);
    }

    carry.drain(..complete * frame_bytes);
}

fn decode_sample(bytes: &[u8], encoding: i32) -> f32 {
    match encoding {
        ENCODING_PCM_8BIT => (bytes[0] as f32 - 128.0) / 128.0,
        ENCODING_PCM_FLOAT => f32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]),
        _ => i16::from_le_bytes([bytes[0], bytes[1]]) as f32 / 32768.0,
    }
}

/// Linear resampler that keeps its fractional read position across chunks, so a caller sending
/// something other than 16 kHz still produces a continuous stream for the model.
struct Resampler {
    step: f64,
    pos: f64,
    pending: Vec<f32>,
}

impl Resampler {
    fn new(from: u32, to: u32) -> Self {
        Self {
            step: from as f64 / to as f64,
            pos: 0.0,
            pending: Vec::new(),
        }
    }

    fn push(&mut self, input: &[f32], out: &mut Vec<f32>) {
        if self.step == 1.0 {
            out.extend_from_slice(input);
            return;
        }

        self.pending.extend_from_slice(input);
        while self.pos + 1.0 < self.pending.len() as f64 {
            let index = self.pos as usize;
            let frac = (self.pos - index as f64) as f32;
            out.push(self.pending[index] * (1.0 - frac) + self.pending[index + 1] * frac);
            self.pos += self.step;
        }

        let consumed = self.pos as usize;
        if consumed > 0 {
            self.pending.drain(..consumed);
            self.pos -= consumed as f64;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_samples_eq(actual: &[f32], expected: &[f32]) {
        assert_eq!(actual.len(), expected.len());
        for (index, (actual, expected)) in actual.iter().zip(expected).enumerate() {
            assert!(
                (actual - expected).abs() < 1e-6,
                "sample {index}: expected {expected}, got {actual}"
            );
        }
    }

    #[test]
    fn source_format_rejects_unsupported_declarations() {
        assert!(SourceFormat::new(3_999, 1, ENCODING_PCM_16BIT).is_none());
        assert!(SourceFormat::new(192_001, 1, ENCODING_PCM_16BIT).is_none());
        assert!(SourceFormat::new(16_000, 0, ENCODING_PCM_16BIT).is_none());
        assert!(SourceFormat::new(16_000, 9, ENCODING_PCM_16BIT).is_none());
        assert!(SourceFormat::new(16_000, 1, -1).is_none());
    }

    #[test]
    fn source_format_accepts_boundary_rates_channels_and_encodings() {
        let low = SourceFormat::new(4_000, 1, ENCODING_PCM_8BIT).unwrap();
        let high = SourceFormat::new(192_000, 8, ENCODING_PCM_FLOAT).unwrap();
        let pcm16 = SourceFormat::new(16_000, 2, ENCODING_PCM_16BIT).unwrap();

        assert_eq!(low.frame_bytes(), 1);
        assert_eq!(high.frame_bytes(), 32);
        assert_eq!(pcm16.frame_bytes(), 4);
    }

    #[test]
    fn decode_sample_normalizes_supported_pcm_encodings() {
        assert_samples_eq(
            &[
                decode_sample(&[0], ENCODING_PCM_8BIT),
                decode_sample(&[128], ENCODING_PCM_8BIT),
                decode_sample(&[255], ENCODING_PCM_8BIT),
            ],
            &[-1.0, 0.0, 127.0 / 128.0],
        );
        assert_samples_eq(
            &[
                decode_sample(&i16::MIN.to_le_bytes(), ENCODING_PCM_16BIT),
                decode_sample(&0i16.to_le_bytes(), ENCODING_PCM_16BIT),
                decode_sample(&i16::MAX.to_le_bytes(), ENCODING_PCM_16BIT),
            ],
            &[-1.0, 0.0, i16::MAX as f32 / 32768.0],
        );
        assert_samples_eq(
            &[decode_sample(&0.25f32.to_le_bytes(), ENCODING_PCM_FLOAT)],
            &[0.25],
        );
    }

    #[test]
    fn drain_mono_frames_downmixes_multichannel_pcm() {
        let format = SourceFormat::new(16_000, 2, ENCODING_PCM_16BIT).unwrap();
        let mut carry = Vec::new();
        carry.extend_from_slice(&16_384i16.to_le_bytes());
        carry.extend_from_slice(&(-16_384i16).to_le_bytes());
        carry.extend_from_slice(&8_192i16.to_le_bytes());
        carry.extend_from_slice(&16_384i16.to_le_bytes());
        let mut out = Vec::new();

        drain_mono_frames(&mut carry, &format, &mut out);

        assert_samples_eq(&out, &[0.0, 0.375]);
        assert!(carry.is_empty());
    }

    #[test]
    fn drain_mono_frames_preserves_partial_frames_between_chunks() {
        let format = SourceFormat::new(16_000, 2, ENCODING_PCM_16BIT).unwrap();
        let frame = [1_024i16.to_le_bytes(), 3_072i16.to_le_bytes()].concat();
        let mut carry = frame[..3].to_vec();
        let mut out = Vec::new();

        drain_mono_frames(&mut carry, &format, &mut out);
        assert!(out.is_empty());
        assert_eq!(carry, frame[..3]);

        carry.extend_from_slice(&frame[3..]);
        drain_mono_frames(&mut carry, &format, &mut out);
        assert_samples_eq(&out, &[0.0625]);
        assert!(carry.is_empty());
    }

    #[test]
    fn resampler_passes_through_target_rate_audio() {
        let mut resampler = Resampler::new(16_000, 16_000);
        let mut out = Vec::new();

        resampler.push(&[0.0, 0.25], &mut out);
        resampler.push(&[0.5, 1.0], &mut out);

        assert_samples_eq(&out, &[0.0, 0.25, 0.5, 1.0]);
    }

    #[test]
    fn resampler_upsamples_continuously_across_chunks() {
        let mut resampler = Resampler::new(8_000, 16_000);
        let mut out = Vec::new();

        resampler.push(&[0.0, 1.0, 2.0], &mut out);
        resampler.push(&[3.0], &mut out);

        assert_samples_eq(&out, &[0.0, 0.5, 1.0, 1.5, 2.0, 2.5]);
    }

    #[test]
    fn resampler_downsamples_continuously_across_chunks() {
        let mut resampler = Resampler::new(32_000, 16_000);
        let mut out = Vec::new();

        resampler.push(&[0.0, 1.0, 2.0, 3.0, 4.0], &mut out);
        resampler.push(&[5.0, 6.0], &mut out);

        assert_samples_eq(&out, &[0.0, 2.0, 4.0]);
    }
}

// --- Audio + endpointing ------------------------------------------------------

fn audio_callback(shared: &Arc<Endpoint>, data: &[f32], analyzer: &mut AudioAnalyzer) {
    if shared.finalized.load(Ordering::SeqCst) {
        return;
    }

    // RMS -> smoothed level in 0..1 (same scaling as voice_session).
    let mut sum = 0.0f32;
    for &x in data {
        sum += x * x;
    }
    let rms = (sum / (data.len().max(1) as f32)).sqrt();
    let level = (rms * 6.0).clamp(0.0, 1.0);

    let is_speech = level > MIN_SPEECH_LEVEL && level > analyzer.noise_floor + SPEECH_MARGIN;

    shared.audio_buffer.lock().unwrap().push(data);

    if is_speech {
        shared.last_voice_ms.store(
            shared.started_at.elapsed().as_millis() as u64,
            Ordering::Relaxed,
        );
        // First detected speech -> notify beginningOfSpeech exactly once.
        if shared
            .speech_started
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_ok()
        {
            if let Ok(mut env) = shared.jvm.attach_current_thread() {
                call_void(&mut env, shared.target.as_obj(), "onBeginningOfSpeech");
            }
        }
    } else {
        // Slowly adapt the noise floor while no speech is present.
        analyzer.noise_floor = analyzer.noise_floor * 0.95 + level * 0.05;
    }

    // Throttled mic-level updates for the keyboard's waveform UI.
    if analyzer.last_level_sent.elapsed() >= Duration::from_millis(LEVEL_UPDATE_MS) {
        analyzer.last_level_sent = Instant::now();
        if let Ok(mut env) = shared.jvm.attach_current_thread() {
            call_rms(&mut env, shared.target.as_obj(), level * 10.0);
        }
    }
}

fn endpoint_monitor(shared: Arc<Endpoint>, stream: Arc<Mutex<Option<SendStream>>>) {
    loop {
        std::thread::sleep(Duration::from_millis(ENDPOINT_MONITOR_MS));

        if shared.cancelled.load(Ordering::SeqCst) || shared.finalized.load(Ordering::SeqCst) {
            return;
        }

        let elapsed = shared.started_at.elapsed();
        let speech = shared.speech_started.load(Ordering::SeqCst);
        let silence = Duration::from_millis(
            (elapsed.as_millis() as u64)
                .saturating_sub(shared.last_voice_ms.load(Ordering::Relaxed)),
        );

        // A caller streaming its own audio decides when the utterance ends; only the hard cap,
        // which bounds how much audio a session may buffer, still applies to it.
        let done = if shared.caller_endpointed {
            elapsed >= Duration::from_millis(MAX_SESSION_MS)
        } else {
            (speech && silence >= Duration::from_millis(SILENCE_MS))
                || elapsed >= Duration::from_millis(MAX_SESSION_MS)
                || (!speech && elapsed >= Duration::from_millis(NO_SPEECH_TIMEOUT_MS))
        };

        if done {
            finalize(shared, stream);
            return;
        }
    }
}

/// Stop capture, run the model on the buffered audio and deliver results/error.
/// Idempotent: only the first caller (monitor or explicit stop) does the work.
fn finalize(shared: Arc<Endpoint>, stream: Arc<Mutex<Option<SendStream>>>) {
    if shared
        .finalized
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        return; // already finalised/cancelled
    }
    shared.inference_running.store(true, Ordering::SeqCst);

    // Stop the microphone (also drops the audio callback's Arc<Endpoint>).
    *stream.lock().unwrap() = None;

    let (tail, total_samples) = {
        let mut capture = shared.audio_buffer.lock().unwrap();
        (capture.take_tail(), capture.total_samples())
    };
    let speech = shared.speech_started.load(Ordering::SeqCst);

    let mut env = match shared.jvm.attach_current_thread() {
        Ok(e) => e,
        Err(_) => {
            shared.inference_running.store(false, Ordering::SeqCst);
            clear_session(&shared);
            return;
        }
    };
    let target = shared.target.as_obj();

    if speech && !shared.cancelled.load(Ordering::SeqCst) {
        call_void(&mut env, target, "onEndOfSpeech");
    }

    // ~0.2s minimum of audio to bother transcribing.
    if total_samples < 3200 {
        if !shared.cancelled.load(Ordering::SeqCst) {
            call_outcome(
                &mut env,
                target,
                1,
                total_samples.saturating_mul(1000) / 16000,
            );
            call_error(&mut env, target, ERROR_NO_MATCH);
        }
        if let Some(completion) = shared.background.cancel() {
            let _ = completion.recv();
        }
        shared.inference_running.store(false, Ordering::SeqCst);
        clear_session(&shared);
        return;
    }

    let Some(completion) = shared.background.finish(tail, total_samples) else {
        if !shared.cancelled.load(Ordering::SeqCst) {
            call_error(&mut env, target, ERROR_SERVER);
        }
        shared.inference_running.store(false, Ordering::SeqCst);
        clear_session(&shared);
        return;
    };
    match completion.recv() {
        Ok(Completion::Finished {
            text,
            audio_ms,
            compute_ms,
            pipeline,
        }) if !shared.cancelled.load(Ordering::SeqCst) => {
            crate::background_transcription::notify_pipeline(&mut env, target, &pipeline);
            call_metrics(&mut env, target, audio_ms, compute_ms);
            match text {
                _ if shared.cancelled.load(Ordering::SeqCst) => {}
                Ok(text) if !text.trim().is_empty() => {
                    call_outcome(&mut env, target, 0, audio_ms);
                    call_results(&mut env, target, &text);
                }
                Ok(_) => {
                    call_outcome(&mut env, target, 2, audio_ms);
                    call_error(&mut env, target, ERROR_NO_MATCH);
                }
                Err(e) => {
                    log::error!("Transcription failed: {}", e);
                    call_error(&mut env, target, ERROR_SERVER);
                }
            }
        }
        Ok(Completion::Cancelled) | Ok(Completion::Finished { .. }) => {}
        Err(_) if !shared.cancelled.load(Ordering::SeqCst) => {
            call_error(&mut env, target, ERROR_SERVER)
        }
        Err(_) => {}
    }

    shared.inference_running.store(false, Ordering::SeqCst);
    clear_session(&shared);
}

/// Clear the global session, but only if it is still *this* session — a newer
/// `startListening` may already have installed a fresh one.
fn clear_session(shared: &Arc<Endpoint>) {
    let mut guard = SESSION.lock().unwrap();
    if let Some(s) = guard.as_ref() {
        if Arc::ptr_eq(&s.shared, shared) {
            *guard = None;
            release_session_owner(OWNER_RECOGNITION_SERVICE);
        }
    }
}
