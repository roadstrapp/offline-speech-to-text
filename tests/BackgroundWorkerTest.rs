//! Compile the real worker without JNI/native inference. Only its external
//! channel/JNI/engine adapters are fakes; capture, planning and padding are real.
extern crate self as crossbeam_channel;
extern crate self as jni;
extern crate self as log;
#[macro_export]
macro_rules! error { ($($args:tt)*) => { eprintln!($($args)*); }; }

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{mpsc, Arc, Condvar, Mutex, OnceLock};
use std::time::Duration;

pub struct Sender<T>(mpsc::Sender<T>);
pub struct Receiver<T>(Arc<Mutex<mpsc::Receiver<T>>>);
impl<T> Clone for Receiver<T> {
    fn clone(&self) -> Self {
        Self(self.0.clone())
    }
}
impl<T> Sender<T> {
    pub fn send(&self, value: T) -> Result<(), mpsc::SendError<T>> {
        self.0.send(value)
    }
}
impl<T> Receiver<T> {
    pub fn recv(&self) -> Result<T, mpsc::RecvError> {
        self.0.lock().unwrap().recv()
    }
    pub fn recv_timeout(&self, timeout: Duration) -> Result<T, mpsc::RecvTimeoutError> {
        self.0.lock().unwrap().recv_timeout(timeout)
    }
}
pub fn unbounded<T>() -> (Sender<T>, Receiver<T>) {
    let (tx, rx) = mpsc::channel();
    (Sender(tx), Receiver(Arc::new(Mutex::new(rx))))
}
pub fn bounded<T>(_: usize) -> (Sender<T>, Receiver<T>) {
    unbounded()
}

pub mod objects {
    pub struct JObject;
    pub struct GlobalRef;
    impl GlobalRef {
        pub fn as_obj(&self) -> &JObject {
            &JObject
        }
    }
    pub enum JValue {
        Long(i64),
        Int(i32),
    }
    pub struct Returned;
    impl Returned {
        pub fn j(&self) -> Result<i64, ()> {
            Ok(42)
        }
    }
}
static CLEARED: AtomicUsize = AtomicUsize::new(0);
pub struct JNIEnv;
impl JNIEnv {
    pub fn call_method(
        &mut self,
        _: &objects::JObject,
        name: &str,
        _: &str,
        _: &[objects::JValue],
    ) -> Result<objects::Returned, ()> {
        if name == "getTroubleshootingSessionId" {
            Ok(objects::Returned)
        } else {
            Err(())
        }
    }
    pub fn exception_clear(&mut self) -> Result<(), ()> {
        CLEARED.fetch_add(1, Ordering::SeqCst);
        Ok(())
    }
}
pub struct JavaVM;
impl JavaVM {
    pub fn attach_current_thread(&self) -> Result<JNIEnv, ()> {
        Ok(JNIEnv)
    }
}

#[path = "../src/audio.rs"]
mod audio;
#[path = "../src/audio_finalization.rs"]
mod audio_finalization;
#[path = "../src/background_chunking.rs"]
mod background_chunking;
#[path = "../src/background_transcription.rs"]
mod background_transcription;
#[path = "../src/long_audio.rs"]
mod long_audio;

#[derive(Default)]
struct Control {
    calls: usize,
    refreshes: usize,
    panic_on_refresh: bool,
    entered: bool,
    release: bool,
    block: bool,
    fail_at: Option<usize>,
    panic_at: Option<usize>,
}
type SharedControl = Arc<(Mutex<Control>, Condvar)>;

mod engine {
    use super::*;
    static ENGINE: OnceLock<Mutex<Option<Arc<Mutex<Engine>>>>> = OnceLock::new();
    pub struct Engine {
        pub limit: Option<usize>,
        pub control: SharedControl,
    }
    pub struct TranscriptionJob {
        count: usize,
        text: String,
    }
    impl TranscriptionJob {
        pub fn finish(self) -> String {
            self.text + " | cleaned once"
        }
    }
    pub fn install(engine: Engine) {
        *ENGINE.get_or_init(|| Mutex::new(None)).lock().unwrap() =
            Some(Arc::new(Mutex::new(engine)));
    }
    pub fn get_engine() -> Option<Arc<Mutex<Engine>>> {
        ENGINE
            .get_or_init(|| Mutex::new(None))
            .lock()
            .unwrap()
            .clone()
    }
    pub fn ensure_loaded_from_thread(
        _: &Arc<JavaVM>,
        _: &objects::GlobalRef,
    ) -> Result<(), String> {
        Err("not loaded".into())
    }
    impl Engine {
        pub fn background_window_limit(&self) -> Option<usize> {
            self.limit
        }
        pub fn diagnostic_family(&self) -> i32 {
            2
        }
        pub fn refresh_final_terms(&self, _: &mut TranscriptionJob) {
            let mut state = self.control.0.lock().unwrap();
            state.refreshes += 1;
            let panic = state.panic_on_refresh;
            drop(state);
            assert!(!panic, "injected term refresh panic");
        }
        pub fn begin_transcription(&self) -> TranscriptionJob {
            TranscriptionJob {
                count: 0,
                text: String::new(),
            }
        }
        pub fn prepared_window_count(job: &TranscriptionJob) -> usize {
            job.count
        }
        fn decode(&mut self, samples: &[f32]) -> Result<String, String> {
            let (state, changed) = &*self.control;
            let mut state = state.lock().unwrap();
            state.calls += 1;
            let call = state.calls;
            state.entered = true;
            changed.notify_all();
            while state.block && !state.release {
                let (next, timeout) = changed.wait_timeout(state, Duration::from_secs(5)).unwrap();
                state = next;
                assert!(!timeout.timed_out(), "test did not release decode");
            }
            let fail = state.fail_at == Some(call);
            let panic = state.panic_at == Some(call);
            drop(state);
            assert!(!panic, "injected decode panic");
            if fail {
                return Err("injected window failure".into());
            }
            // A reproducible decoder sensitive to every sample and padding.
            let hash = samples.iter().fold(0u64, |hash, sample| {
                hash.wrapping_mul(31).wrapping_add(sample.to_bits() as u64)
            });
            Ok(format!("window {:x} has {} samples", hash, samples.len()))
        }
        pub fn prepare_complete_window(
            &mut self,
            job: &mut TranscriptionJob,
            mut audio: Vec<f32>,
        ) -> Result<(), String> {
            audio_finalization::append_final_decode_silence(&mut audio);
            // Deliberately reproduce the former take/restore panic hazard.
            // Worker panic recovery must discard this arbitrarily mutated job.
            let previous = std::mem::take(&mut job.text);
            let text = match self.decode(&audio) {
                Ok(text) => text,
                Err(error) => {
                    job.text = previous;
                    return Err(error);
                }
            };
            job.text = long_audio::stitch(&previous, &text);
            job.count += 1;
            Ok(())
        }
        pub fn transcribe_into(
            &mut self,
            job: &mut TranscriptionJob,
            samples: &[f32],
        ) -> Result<(), String> {
            let windows = self
                .limit
                .map(|limit| long_audio::windows(samples, limit))
                .unwrap_or_else(|| vec![0..samples.len()]);
            assert!(job.count < windows.len());
            for (index, window) in windows.iter().enumerate().skip(job.count) {
                let mut audio = samples[window.clone()].to_vec();
                if index + 1 < windows.len() {
                    audio_finalization::append_final_decode_silence(&mut audio);
                }
                let text = self.decode(&audio)?;
                job.text = long_audio::stitch(&job.text, &text);
            }
            Ok(())
        }
    }
}

static TEST_LOCK: Mutex<()> = Mutex::new(());
fn setup(
    limit: Option<usize>,
    fail_at: Option<usize>,
    panic_at: Option<usize>,
    block: bool,
) -> (
    background_transcription::BackgroundTranscriber,
    Arc<Mutex<background_chunking::CaptureBuffer>>,
    SharedControl,
) {
    let control = Arc::new((
        Mutex::new(Control {
            fail_at,
            panic_at,
            block,
            ..Control::default()
        }),
        Condvar::new(),
    ));
    engine::install(engine::Engine {
        limit,
        control: control.clone(),
    });
    let capture = Arc::new(Mutex::new(background_chunking::CaptureBuffer::default()));
    let worker = background_transcription::BackgroundTranscriber::start_with_capture(
        Arc::new(JavaVM),
        objects::GlobalRef,
        Some(capture.clone()),
    );
    (worker, capture, control)
}
fn audio() -> Vec<f32> {
    (0..55_000)
        .map(|i| ((i % 37) as f32 - 18.0) / 20.0)
        .collect()
}
fn wait_for(control: &SharedControl, calls: usize) {
    let (state, changed) = &**control;
    let mut state = state.lock().unwrap();
    while state.calls < calls {
        let (next, timeout) = changed.wait_timeout(state, Duration::from_secs(5)).unwrap();
        state = next;
        assert!(!timeout.timed_out(), "worker did not decode");
    }
}
fn release(control: &SharedControl) {
    let mut state = control.0.lock().unwrap();
    state.release = true;
    control.1.notify_all();
}
fn finish(
    worker: &background_transcription::BackgroundTranscriber,
    capture: &Arc<Mutex<background_chunking::CaptureBuffer>>,
) -> (String, usize) {
    let mut capture = capture.lock().unwrap();
    let count = capture.total_samples();
    let samples = capture.take_tail();
    drop(capture);
    match worker
        .finish(samples, count)
        .unwrap()
        .recv_timeout(Duration::from_secs(5))
        .unwrap()
    {
        background_transcription::Completion::Finished { text, pipeline, .. } => {
            (text.unwrap(), pipeline.prepared_windows)
        }
        _ => panic!("unexpected cancellation"),
    }
}

#[test]
fn preparation_matches_deferred_audio_and_cleans_only_once() {
    let _serial = TEST_LOCK.lock().unwrap();
    let original = audio();
    let (plain, capture, _) = setup(Some(16_000), None, None, false);
    capture.lock().unwrap().push(&original);
    let (expected, _) = finish(&plain, &capture);
    let (early, capture, control) = setup(Some(16_000), None, None, false);
    capture.lock().unwrap().push(&original);
    early.request_preparation();
    wait_for(&control, 1);
    let (actual, prepared) = finish(&early, &capture);
    assert_eq!(actual, expected);
    assert_eq!(prepared, 1);
    assert_eq!(control.0.lock().unwrap().refreshes, 1);
    assert_eq!(actual.matches("cleaned once").count(), 1);
}

#[test]
fn failed_preparation_retains_original_and_retries_only_at_stop() {
    let _serial = TEST_LOCK.lock().unwrap();
    for panic_at in [None, Some(1)] {
        let (plain, capture, _) = setup(Some(16_000), None, None, false);
        capture.lock().unwrap().push(&audio());
        let (expected, _) = finish(&plain, &capture);
        let (early, capture, control) = setup(Some(16_000), Some(1), panic_at, false);
        capture.lock().unwrap().push(&audio());
        early.request_preparation();
        wait_for(&control, 1);
        let (actual, prepared) = finish(&early, &capture);
        assert_eq!(actual, expected);
        assert_eq!(prepared, 0);
        assert_eq!(
            control.0.lock().unwrap().refreshes,
            usize::from(panic_at.is_none()),
            "even zero-window capture jobs must refresh terms at Stop"
        );
    }
}

#[test]
fn failure_after_one_success_preserves_cached_prefix_without_duplication() {
    let _serial = TEST_LOCK.lock().unwrap();
    let (plain, capture, _) = setup(Some(16_000), None, None, false);
    capture.lock().unwrap().push(&audio());
    let (expected, _) = finish(&plain, &capture);
    let (worker, capture, control) = setup(Some(16_000), Some(2), None, false);
    capture.lock().unwrap().push(&audio());
    worker.request_preparation();
    wait_for(&control, 1);
    worker.request_preparation();
    wait_for(&control, 2);
    let (actual, prepared) = finish(&worker, &capture);
    assert_eq!(actual, expected);
    assert_eq!(prepared, 1);
}

#[test]
fn panic_after_a_success_discards_mutated_cache_and_decodes_every_word() {
    let _serial = TEST_LOCK.lock().unwrap();
    let (plain, capture, _) = setup(Some(16_000), None, None, false);
    capture.lock().unwrap().push(&audio());
    let (expected, _) = finish(&plain, &capture);
    let (worker, capture, control) = setup(Some(16_000), None, Some(2), false);
    capture.lock().unwrap().push(&audio());
    worker.request_preparation();
    wait_for(&control, 1);
    worker.request_preparation();
    wait_for(&control, 2);
    let (actual, prepared) = finish(&worker, &capture);
    assert_eq!(actual, expected);
    assert_eq!(prepared, 0);
}

#[test]
fn default_worker_stop_then_discard_still_delivers_cleanup() {
    let _serial = TEST_LOCK.lock().unwrap();
    let (_, _, control) = setup(None, None, None, true);
    let worker = background_transcription::BackgroundTranscriber::start(
        Arc::new(JavaVM),
        objects::GlobalRef,
    );
    let completion = worker.finish(audio(), 55_000).unwrap();
    wait_for(&control, 1);
    assert!(worker.cancel().is_none());
    release(&control);
    assert!(matches!(
        completion.recv_timeout(Duration::from_secs(5)).unwrap(),
        background_transcription::Completion::Finished { .. }
    ));
}

#[test]
fn cancel_during_preparation_suppresses_delivery_and_finish() {
    let _serial = TEST_LOCK.lock().unwrap();
    let (worker, capture, control) = setup(Some(16_000), None, None, true);
    capture.lock().unwrap().push(&audio());
    worker.request_preparation();
    wait_for(&control, 1);
    let cancelled = worker.cancel().unwrap();
    assert!(worker.finish(audio(), 55_000).is_none());
    release(&control);
    assert!(matches!(
        cancelled.recv_timeout(Duration::from_secs(5)).unwrap(),
        background_transcription::Completion::Cancelled
    ));
}

#[test]
fn stop_setup_panic_sends_one_error_and_never_decodes_or_finishes_again() {
    let _serial = TEST_LOCK.lock().unwrap();
    let (worker, capture, control) = setup(Some(16_000), None, None, false);
    capture.lock().unwrap().push(&audio());
    worker.request_preparation();
    wait_for(&control, 1);
    control.0.lock().unwrap().panic_on_refresh = true;
    let receiver = worker.finish(audio(), 55_000).unwrap();
    match receiver.recv_timeout(Duration::from_secs(5)).unwrap() {
        background_transcription::Completion::Finished { text, .. } => {
            assert_eq!(text.unwrap_err(), "transcription setup failed unexpectedly")
        }
        _ => panic!("missing setup error completion"),
    }
    assert_eq!(control.0.lock().unwrap().calls, 1);
    assert!(receiver.recv_timeout(Duration::from_millis(10)).is_err());
}

#[test]
fn stop_then_discard_preserves_final_cleanup_callback() {
    let _serial = TEST_LOCK.lock().unwrap();
    let (worker, capture, control) = setup(Some(16_000), None, None, true);
    capture.lock().unwrap().push(&audio());
    worker.request_preparation();
    wait_for(&control, 1);
    let completion = worker.finish(audio(), 55_000).unwrap();
    assert!(worker.cancel().is_none());
    release(&control);
    assert!(matches!(
        completion.recv_timeout(Duration::from_secs(5)).unwrap(),
        background_transcription::Completion::Finished { .. }
    ));
}

#[test]
fn short_audio_and_whisper_never_decode_during_capture() {
    let _serial = TEST_LOCK.lock().unwrap();
    for limit in [None, Some(60 * 16_000)] {
        let (worker, capture, control) = setup(limit, None, None, false);
        capture.lock().unwrap().push(&audio());
        for _ in 0..100 {
            worker.request_preparation();
        }
        let (_, prepared) = finish(&worker, &capture);
        assert_eq!(prepared, 0);
        assert_eq!(control.0.lock().unwrap().calls, 1);
    }
}

#[test]
fn capture_lock_contention_does_not_block_the_monitor() {
    let _serial = TEST_LOCK.lock().unwrap();
    let (worker, capture, _) = setup(Some(16_000), None, None, false);
    let held = capture.lock().unwrap();
    worker.request_preparation();
    drop(held);
    capture.lock().unwrap().push(&audio());
    let (_, prepared) = finish(&worker, &capture);
    assert_eq!(prepared, 0);
}

#[test]
fn missing_optional_pipeline_method_clears_jni_exception() {
    let before = CLEARED.load(Ordering::SeqCst);
    background_transcription::notify_pipeline(
        &mut JNIEnv,
        &objects::JObject,
        &background_transcription::PipelineMetrics {
            session_id: 42,
            ..Default::default()
        },
    );
    assert!(CLEARED.load(Ordering::SeqCst) > before);
}
