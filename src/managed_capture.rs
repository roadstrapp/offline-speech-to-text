//! The microphone capture behind every OSTT-owned recording, with the
//! optional Bluetooth route and its recovery.
//!
//! Opening asks Java for the Bluetooth route (setting, or the recording
//! screen's one-shot choice) and opens the stream on it, or on the phone
//! microphone. A Bluetooth stream is watched (`capture_health.rs`): one that
//! delivers no frames, stops delivering, delivers only exact zeros, or reports
//! an AAudio error (an LE Audio link reconfiguring underneath it disconnects
//! the stream) is closed and reopened once on Bluetooth, with the route
//! re-asserted and settled. If that one is dead too, the recording continues
//! on the phone microphone and Java plays a cue and updates the recording
//! screen. Both streams feed the same callbacks, so audio captured so far is
//! kept. Recovery runs on the watchdog thread and never holds the stream lock
//! while it waits for a route, so pause/stop stay responsive.

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use jni::objects::{GlobalRef, JObject};
use jni::JNIEnv;

use crate::bluetooth_mic::{self, RouteGuard};
use crate::capture_health::{verdict, Dead, Health};
use crate::capture_stream::{self, CaptureStream};

type DataFn = Box<dyn FnMut(&[f32]) + Send>;
type ErrorFn = Box<dyn FnMut(String) + Send>;

const WATCH_MS: u64 = 100;

/// How a Bluetooth stream is captured. AudioRecord first: on LE Audio hearing
/// aids an AAudio stream delivered only zeros where AudioRecord records.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Method {
    AudioRecord,
    AAudio,
}

impl Method {
    fn other(self) -> Self {
        match self {
            Method::AudioRecord => Method::AAudio,
            Method::AAudio => Method::AudioRecord,
        }
    }

    fn label(self) -> &'static str {
        match self {
            Method::AudioRecord => "AudioRecord",
            Method::AAudio => "AAudio",
        }
    }
}

struct Inner {
    // Field order is the release order: the stream closes before the route.
    stream: Option<CaptureStream>,
    route: Option<RouteGuard>,
    paused: bool,
    playing: bool,
    epoch: u64,
}

struct Shared {
    inner: Mutex<Inner>,
    health: Health,
    closed: AtomicBool,
    next_epoch: AtomicU64,
    active_epoch: AtomicU64,
    ready: Mutex<Option<Box<dyn FnOnce() + Send>>>,
    /// The current stream is a verified Bluetooth stream under watch.
    bluetooth: Arc<AtomicBool>,
    last_error: Mutex<Option<String>>,
    /// Debug only: Bluetooth samples are replaced by zeros (an aid's muted
    /// microphone), to exercise the fallback end to end.
    simulate_zeros: AtomicBool,
    /// Debug only: watch and log, never recover (to observe a slow route).
    observe_only: AtomicBool,
    data: Arc<Mutex<DataFn>>,
    error: Arc<Mutex<ErrorFn>>,
    jvm: Arc<jni::JavaVM>,
    class: Option<GlobalRef>,
    context: GlobalRef,
}

// The cpal/AAudio stream handles are only touched under `inner`.
unsafe impl Send for Shared {}
unsafe impl Sync for Shared {}

pub struct ManagedCapture {
    shared: Arc<Shared>,
}

impl ManagedCapture {
    /// Creates one recording capture; routing/opening runs on its owned worker.
    /// Resolve the app class on a thread entered from Java before dispatch.
    pub fn open<D, E>(
        env: &mut JNIEnv,
        jvm: &Arc<jni::JavaVM>,
        context: &JObject,
        data: D,
        error: E,
    ) -> Result<Self, String>
    where
        D: FnMut(&[f32]) + Send + 'static,
        E: FnMut(String) + Send + 'static,
    {
        let class = bluetooth_mic::route_class(env);
        Self::open_with_class(env, jvm, context, data, error, class, None)
    }

    /// The Java entry thread resolves the class before handing setup to a worker.
    pub fn open_with_class<D, E>(
        env: &mut JNIEnv, jvm: &Arc<jni::JavaVM>, context: &JObject,
        data: D, error: E, class: Option<GlobalRef>, ready: Option<Box<dyn FnOnce() + Send>>,
    ) -> Result<Self, String>
    where D: FnMut(&[f32]) + Send + 'static, E: FnMut(String) + Send + 'static,
    {
        let context = env.new_global_ref(context).map_err(|e| e.to_string())?;
        let ready = ready.or_else(|| {
            let vm = jvm.clone();
            let target = context.clone();
            Some(Box::new(move || {
                if let Ok(mut env) = vm.attach_current_thread() {
                    if let Ok(text) = env.new_string("Listening...") {
                        let _ = env.call_method(target.as_obj(), "onStatusUpdate", "(Ljava/lang/String;)V", &[(&text).into()]);
                    }
                    let _ = env.exception_clear();
                }
            }) as Box<dyn FnOnce() + Send>)
        });
        let shared = Arc::new(Shared {
            inner: Mutex::new(Inner {
                stream: None,
                route: None,
                paused: false,
                playing: false,
                epoch: 0,
            }),
            health: Health::new(),
            closed: AtomicBool::new(false),
            next_epoch: AtomicU64::new(1),
            active_epoch: AtomicU64::new(0),
            ready: Mutex::new(ready),
            bluetooth: Arc::new(AtomicBool::new(false)),
            last_error: Mutex::new(None),
            simulate_zeros: AtomicBool::new(false),
            observe_only: AtomicBool::new(false),
            data: Arc::new(Mutex::new(Box::new(data))),
            error: Arc::new(Mutex::new(Box::new(error))),
            jvm: jvm.clone(),
            class,
            context,
        });
        // Setup may wait for a Bluetooth route. Keep every UI entry nonblocking;
        // play/pause/Drop update the owned session while this worker is pending.
        shared.bluetooth.store(true, Ordering::SeqCst); // setup also suspends startup liveness probes
        shared.simulate_zeros.store(debug_marker(env, &shared, "simulateZeros"), Ordering::SeqCst);
        shared.observe_only.store(debug_marker(env, &shared, "observeOnly"), Ordering::SeqCst);
        let choice = shared.class.as_ref().map(|class| bluetooth_mic::take_start_override(env, class));
        let pending = shared.clone();
        std::thread::spawn(move || {
            let Ok(mut env) = pending.jvm.attach_current_thread() else { return; };
            if pending.closed.load(Ordering::SeqCst) { return; }
            let route = pending.class.as_ref().and_then(|class| {
                bluetooth_mic::acquire(&mut env, &pending.jvm, class, pending.context.as_obj(), false, choice)
            });
            if pending.closed.load(Ordering::SeqCst) { drop(route); return; }
            let method = first_method(&mut env, &pending);
            match open_stream(&mut env, &pending, route, method) {
                Ok((stream, route, epoch)) => {
                    let bluetooth = route.is_some();
                    if install(&pending, stream, route, epoch) {
                        pending.bluetooth.store(bluetooth, Ordering::SeqCst);
                        if bluetooth { watchdog(pending.clone(), method); }
                    }
                }
                Err(error) => {
                    pending.bluetooth.store(false, Ordering::SeqCst);
                    if !pending.closed.load(Ordering::SeqCst) {
                        if let Ok(mut callback) = pending.error.lock() { callback(error); }
                    }
                }
            }
        });
        Ok(Self { shared })
    }

    pub fn play(&self) -> Result<(), String> {
        let mut inner = self.shared.inner.lock().unwrap_or_else(|p| p.into_inner());
        if let Some(stream) = inner.stream.as_ref() {
            stream.play()?;
            if let Some(ready) = self.shared.ready.lock().unwrap_or_else(|p| p.into_inner()).take() { ready(); }
            self.shared.active_epoch.store(inner.epoch, Ordering::SeqCst);
        }
        inner.paused = false;
        inner.playing = true;
        self.shared.health.restart();
        Ok(())
    }

    pub fn pause(&self) -> Result<(), String> {
        let mut inner = self.shared.inner.lock().unwrap_or_else(|p| p.into_inner());
        inner.paused = true;
        self.shared.active_epoch.store(0, Ordering::SeqCst);
        drop(self.shared.data.lock().unwrap_or_else(|p| p.into_inner()));
        match inner.stream.as_ref() {
            Some(stream) => stream.pause(),
            None => Ok(()),
        }
    }

    /// True while a Bluetooth stream is under the recovery watchdog; other
    /// liveness checks then leave it to the watchdog.
    pub fn bluetooth_watched(&self) -> Arc<AtomicBool> {
        self.shared.bluetooth.clone()
    }
}

impl Drop for ManagedCapture {
    fn drop(&mut self) {
        self.shared.active_epoch.store(0, Ordering::SeqCst);
        self.shared.closed.store(true, Ordering::SeqCst);
        drop(self.shared.data.lock().unwrap_or_else(|p| p.into_inner()));
        self.shared.bluetooth.store(false, Ordering::SeqCst);
        let (stream, route) = {
            let mut inner = self.shared.inner.lock().unwrap_or_else(|p| p.into_inner());
            (inner.stream.take(), inner.route.take())
        };
        drop(stream);
        drop(route);
    }
}

/// A boolean static `BluetoothMicRoute.<method>(Context)` (debug markers).
fn debug_marker(env: &mut JNIEnv, shared: &Shared, method: &str) -> bool {
    let Some(class) = &shared.class else {
        return false;
    };
    let value = env
        .call_static_method(
            class,
            method,
            "(Landroid/content/Context;)Z",
            &[jni::objects::JValue::Object(shared.context.as_obj())],
        )
        .and_then(|value| value.z())
        .unwrap_or(false);
    if env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
    }
    value
}

/// The first Bluetooth capture method; a debug marker can force AAudio.
fn first_method(env: &mut JNIEnv, shared: &Shared) -> Method {
    if debug_marker(env, shared, "preferAAudio") {
        Method::AAudio
    } else {
        Method::AudioRecord
    }
}

/// Callbacks for one stream; `routed` decides where its errors go.
fn callbacks(
    shared: &Arc<Shared>,
    routed: &Arc<AtomicBool>,
) -> (
    impl FnMut(&[f32]) + Send + 'static,
    impl FnMut(String) + Send + 'static,
    u64,
) {
    let epoch = shared.next_epoch.fetch_add(1, Ordering::SeqCst);
    let data = {
        let shared = shared.clone();
        let routed = routed.clone();
        let mut zeros: Vec<f32> = Vec::new();
        move |samples: &[f32]| {
            let samples = if routed.load(Ordering::Relaxed)
                && shared.simulate_zeros.load(Ordering::Relaxed)
            {
                zeros.clear();
                zeros.resize(samples.len(), 0.0);
                &zeros[..]
            } else {
                samples
            };
            if shared.closed.load(Ordering::SeqCst) || shared.active_epoch.load(Ordering::SeqCst) != epoch { return; }
            if let Ok(mut data) = shared.data.lock() {
                if shared.closed.load(Ordering::SeqCst) || shared.active_epoch.load(Ordering::SeqCst) != epoch { return; }
                shared.health.record(samples);
                data(samples);
            }
        }
    };
    // A Bluetooth stream's errors go to the watchdog (it recovers), a phone
    // stream's to the recording as before.
    let error = {
        let shared = shared.clone();
        let routed = routed.clone();
        move |message: String| {
            if shared.closed.load(Ordering::SeqCst) || shared.active_epoch.load(Ordering::SeqCst) != epoch { return; }
            if routed.load(Ordering::SeqCst) {
                *shared.last_error.lock().unwrap_or_else(|p| p.into_inner()) = Some(message);
                shared.health.mark_error();
            } else if let Ok(mut error) = shared.error.lock() {
                error(message);
            }
        }
    };
    (data, error, epoch)
}

/// Opens one stream feeding the shared callbacks: on `route` with `method`
/// when given and verified by Java, else on the phone microphone. Returns
/// the route only when the stream really runs on it.
fn open_stream(
    env: &mut JNIEnv,
    shared: &Arc<Shared>,
    route: Option<RouteGuard>,
    method: Method,
) -> Result<(CaptureStream, Option<RouteGuard>, u64), String> {
    let device = cpal::traits::HostTrait::default_input_device(&cpal::default_host())
        .ok_or_else(|| "no microphone available".to_string())?;
    let route = match (route, method) {
        (Some(route), Method::AudioRecord) => {
            let routed = Arc::new(AtomicBool::new(true));
            let (data, error, epoch) = callbacks(shared, &routed);
            match crate::bt_audio_record::JavaStream::open(
                env,
                &shared.jvm,
                &route,
                Box::new(data),
                Box::new(error),
            ) {
                Some((stream, opened)) if route.confirm(env, &opened) => {
                    return Ok((CaptureStream::Java(stream), Some(route), epoch));
                }
                // Not on the Bluetooth input after all, or refused: try AAudio
                // on the same route before the phone microphone.
                Some((stream, _)) => {
                    drop(stream);
                    log(env, shared, "capture: AudioRecord not on the Bluetooth input; trying AAudio");
                    Some(route)
                }
                None => {
                    log(env, shared, "capture: AudioRecord could not be started on Bluetooth; trying AAudio");
                    Some(route)
                }
            }
        }
        (route, _) => route,
    };
    let routed_stream = Arc::new(AtomicBool::new(false));
    let (data, error, epoch) = callbacks(shared, &routed_stream);
    let route_env = &mut *env;
    let route_open = route.as_ref().map(|route| {
        (route.input_device_id(), move |opened: &bluetooth_mic::OpenedStream| {
            route.confirm(route_env, opened)
        })
    });
    let (stream, routed) = capture_stream::open_input(&device, route_open, data, error)?;
    routed_stream.store(routed, Ordering::SeqCst);
    Ok((stream, if routed { route } else { None }, epoch))
}

fn log(env: &mut JNIEnv, shared: &Shared, message: &str) {
    if let Some(class) = &shared.class {
        bluetooth_mic::log(env, class, message);
    }
}

/// Puts a recovered stream in place, unless the recording ended meanwhile.
fn install(shared: &Shared, stream: CaptureStream, route: Option<RouteGuard>, epoch: u64) -> bool {
    let mut inner = shared.inner.lock().unwrap_or_else(|p| p.into_inner());
    if shared.closed.load(Ordering::SeqCst) {
        drop(inner);
        drop(stream);
        drop(route);
        return false;
    }
    shared.health.restart();
    inner.epoch = epoch;
    let mut terminal_error = None;
    if !inner.paused && inner.playing {
        match stream.play() {
            Ok(()) => {
                if let Some(ready) = shared.ready.lock().unwrap_or_else(|p| p.into_inner()).take() { ready(); }
                shared.active_epoch.store(epoch, Ordering::SeqCst);
            }
            Err(e) => {
                *shared.last_error.lock().unwrap_or_else(|p| p.into_inner()) = Some(e.clone());
                shared.health.mark_error();
                if route.is_none() { terminal_error = Some(e); }
            }
        }
    } else if let Err(error) = stream.pause() {
        drop(inner);
        drop(stream);
        drop(route);
        if !shared.closed.load(Ordering::SeqCst) {
            if let Ok(mut callback) = shared.error.lock() { callback(error); }
        }
        return false;
    }

    inner.stream = Some(stream);
    inner.route = route;
    drop(inner);
    if let Some(error) = terminal_error {
        if !shared.closed.load(Ordering::SeqCst) {
            if let Ok(mut callback) = shared.error.lock() { callback(error); }
        }
        return false;
    }
    true
}

fn watchdog(shared: Arc<Shared>, first: Method) {
    let _thread_attachment = shared.jvm.attach_current_thread_permanently();
    let mut reopened = false;
    let mut method = first;
    let mut logged_start = u64::MAX;
    let mut base_frames = 0u64;
    let mut seconds_logged = 0u64;
    let mut first_logged = false;
    let mut sound_logged = false;
    loop {
        std::thread::sleep(Duration::from_millis(WATCH_MS));
        if shared.closed.load(Ordering::SeqCst) || !shared.bluetooth.load(Ordering::SeqCst) {
            return;
        }
        {
            let inner = shared.inner.lock().unwrap_or_else(|p| p.into_inner());
            if inner.paused || !inner.playing {
                continue;
            }
        }
        let health = &shared.health;
        let snapshot = health.snapshot();
        if snapshot.started != logged_start {
            logged_start = snapshot.started;
            base_frames = health.frames();
            seconds_logged = 0;
            first_logged = false;
            sound_logged = false;
        }
        let since_start = snapshot.now.saturating_sub(snapshot.started);
        if let Ok(mut env) = shared.jvm.attach_current_thread() {
            if !first_logged {
                if let Some(first) = health.first_frame_ms().filter(|t| *t >= snapshot.started) {
                    first_logged = true;
                    let message = format!(
                        "capture: first frames {} ms after start",
                        first.saturating_sub(snapshot.started)
                    );
                    log(&mut env, &shared, &message);
                }
            }
            if !sound_logged {
                if let Some(sound) = snapshot.last_nonzero.filter(|t| *t >= snapshot.started) {
                    sound_logged = true;
                    let message = format!(
                        "capture: first sound {} ms after start",
                        sound.saturating_sub(snapshot.started)
                    );
                    log(&mut env, &shared, &message);
                    if let Some(class) = &shared.class {
                        bluetooth_mic::notify_live(&mut env, class);
                    }
                }
            }
            let log_seconds = if shared.observe_only.load(Ordering::Relaxed) { 15 } else { 3 };
            if seconds_logged < log_seconds && since_start >= (seconds_logged + 1) * 1_000 {
                seconds_logged += 1;
                let frames = health.frames();
                let message = format!(
                    "capture: second {} on Bluetooth: {} frames{}",
                    seconds_logged,
                    frames.saturating_sub(base_frames),
                    if snapshot.last_nonzero.is_some_and(|t| t >= snapshot.started) {
                        ""
                    } else {
                        " (only zeros so far)"
                    }
                );
                base_frames = frames;
                log(&mut env, &shared, &message);
            }
        }
        let Some(dead) = verdict(&snapshot) else {
            continue;
        };
        if shared.observe_only.load(Ordering::Relaxed) {
            continue;
        }
        if !recover(&shared, dead, since_start, &mut reopened, &mut method) {
            phone_check(&shared);
            return;
        }
    }
}

/// Returns true to keep watching (the stream was reopened on Bluetooth).
fn recover(
    shared: &Arc<Shared>,
    dead: Dead,
    since_start: u64,
    reopened: &mut bool,
    method: &mut Method,
) -> bool {
    let Ok(mut env) = shared.jvm.attach_current_thread() else {
        return false;
    };
    let detail = shared
        .last_error
        .lock()
        .unwrap()
        .take()
        .map(|e| format!(" ({e})"))
        .unwrap_or_default();
    // Only an error callback (a disconnected stream) is worth a reopen. A
    // stream that never got sound in the startup window, or lost it, is not:
    // a reopen releases the route and restarts the LE Audio setup.
    if dead != Dead::StreamError {
        *reopened = true;
    }
    if let Some(class) = &shared.class {
        bluetooth_mic::set_recovering(&mut env, class, true);
    }
    let message = format!(
        "capture dead on Bluetooth ({}): {}{} after {} ms, {} frames so far; {}",
        method.label(),
        dead.label(),
        detail,
        since_start,
        shared.health.frames(),
        if *reopened {
            "falling back to the phone microphone".to_string()
        } else {
            format!("reopening on Bluetooth with {}", method.other().label())
        }
    );
    log(&mut env, shared, &message);

    let (old_stream, old_route) = {
        let mut inner = shared.inner.lock().unwrap_or_else(|p| p.into_inner());
        if shared.closed.load(Ordering::SeqCst) {
            drop(inner);
            if let Some(class) = &shared.class {
                bluetooth_mic::set_recovering(&mut env, class, false);
            }
            return false;
        }
        (inner.stream.take(), inner.route.take())
    };
    shared.active_epoch.store(0, Ordering::SeqCst);
    drop(shared.data.lock().unwrap_or_else(|p| p.into_inner()));
    drop(old_stream);
    // Clears the communication device, so the retry re-asserts and settles it.
    drop(old_route);

    if !*reopened {
        *reopened = true;
        *method = method.other();
        let route = shared.class.as_ref().and_then(|class| {
            bluetooth_mic::acquire(&mut env, &shared.jvm, class, shared.context.as_obj(), true, None)
        });
        if route.is_some() {
            match open_stream(&mut env, shared, route, *method) {
                Ok((stream, Some(route), epoch)) => {
                    let message = format!("capture: reopened on Bluetooth with {}", method.label());
                    log(&mut env, shared, &message);
                    let installed = install(shared, stream, Some(route), epoch);
                    if let Some(class) = &shared.class {
                        bluetooth_mic::set_recovering(&mut env, class, false);
                    }
                    return installed;
                }
                // Already a phone stream: keep it rather than opening another.
                Ok((stream, None, epoch)) => {
                    shared.bluetooth.store(false, Ordering::SeqCst);
                    let installed = install(shared, stream, None, epoch);
                    if installed {
                        log(&mut env, shared, "capture: continuing on the phone microphone");
                    }
                    if let Some(class) = &shared.class {
                        bluetooth_mic::set_recovering(&mut env, class, false);
                        if installed && !shared.closed.load(Ordering::SeqCst) { bluetooth_mic::notify_fallback(&mut env, class, dead.label()); }
                    }
                    return false;
                }
                Err(_) => {}
            }
        }
        log(&mut env, shared, "capture: Bluetooth could not be reopened");
    }

    shared.bluetooth.store(false, Ordering::SeqCst);
    match open_stream(&mut env, shared, None, *method) {
        Ok((stream, _, epoch)) => {
            let installed = install(shared, stream, None, epoch);
            if installed {
                log(&mut env, shared, "capture: continuing on the phone microphone");
            }
            if let Some(class) = &shared.class {
                bluetooth_mic::set_recovering(&mut env, class, false);
                if installed {
                    bluetooth_mic::notify_fallback(&mut env, class, dead.label());
                }
            }
        }
        Err(e) => {
            let message = format!("capture: phone microphone failed too: {e}");
            log(&mut env, shared, &message);
            if let Some(class) = &shared.class {
                bluetooth_mic::set_recovering(&mut env, class, false);
            }
            if let Ok(mut error) = shared.error.lock() {
                error(e);
            }
        }
    }
    false
}

/// After a fallback, log three seconds of the phone stream so a report shows
/// whether the recording really continued with sound.
fn phone_check(shared: &Arc<Shared>) {
    let _thread_attachment = shared.jvm.attach_current_thread_permanently();
    let started = shared.health.snapshot().started;
    let mut base = shared.health.frames();
    for second in 1..=3 {
        std::thread::sleep(Duration::from_secs(1));
        if shared.closed.load(Ordering::SeqCst) {
            return;
        }
        let snapshot = shared.health.snapshot();
        let frames = shared.health.frames();
        let sound = snapshot.last_nonzero.is_some_and(|t| t >= started);
        let message = format!(
            "capture: second {} on the phone microphone: {} frames{}",
            second,
            frames.saturating_sub(base),
            if sound { ", with sound" } else { " (only zeros so far)" }
        );
        base = frames;
        if let Ok(mut env) = shared.jvm.attach_current_thread() {
            log(&mut env, shared, &message);
        }
    }
}
