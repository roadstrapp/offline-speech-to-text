//! Bluetooth capture through Java `AudioRecord` (`BluetoothAudioRecord.java`).
//!
//! LE Audio hearing aids delivered only digital zeros to an AAudio stream on
//! their input (float, voice-communication preset, explicit device), while an
//! `AudioRecord` with PCM16, the VOICE_COMMUNICATION source and the input as
//! preferred device records them. The Java reader thread converts each block
//! to float and calls `nativeFeed` with an opaque handle to the capture
//! callbacks, which therefore see the same samples as from any other stream.
//! Opaque handles resolve through an Arc registry. Retiring a recorder removes
//! new callback admission; already admitted callbacks keep their state alive.

use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicI64, Ordering};
use std::collections::HashMap;
use once_cell::sync::Lazy;

use jni::objects::{GlobalRef, JClass, JFloatArray, JValue};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

use crate::bluetooth_mic::{OpenedStream, RouteGuard};

type DataFn = Box<dyn FnMut(&[f32]) + Send>;
type ErrorFn = Box<dyn FnMut(String) + Send>;

struct Feed {
    data: DataFn,
    error: ErrorFn,
    scratch: Vec<f32>,
}

pub struct JavaStream {
    jvm: Arc<jni::JavaVM>,
    recorder: GlobalRef,
    handle: jlong,
}

static FEEDS: Lazy<Mutex<HashMap<jlong, Arc<Mutex<Feed>>>>> = Lazy::new(|| Mutex::new(HashMap::new()));
static NEXT_FEED: AtomicI64 = AtomicI64::new(1);

impl JavaStream {
    /// Creates and starts an AudioRecord on `route`'s input. Returns what it
    /// reports about itself for `confirm`, or None when Android refused.
    pub fn open(
        env: &mut JNIEnv,
        jvm: &Arc<jni::JavaVM>,
        route: &RouteGuard,
        data: DataFn,
        error: ErrorFn,
    ) -> Option<(Self, OpenedStream)> {
        let handle = NEXT_FEED.fetch_add(1, Ordering::SeqCst);
        FEEDS.lock().unwrap_or_else(|p| p.into_inner()).insert(handle, Arc::new(Mutex::new(Feed {
            data, error, scratch: Vec::new(),
        })));
        let free = |handle: jlong| { FEEDS.lock().unwrap_or_else(|p| p.into_inner()).remove(&handle); };
        let recorder = env
            .call_static_method(
                route.class(),
                "openRecorder",
                "(JJ)Ldev/notune/transcribe/BluetoothAudioRecord;",
                &[JValue::Long(route.token()), JValue::Long(handle)],
            )
            .and_then(|value| value.l());
        if env.exception_check().unwrap_or(true) {
            let _ = env.exception_clear();
            free(handle);
            return None;
        }
        let recorder = match recorder {
            Ok(object) if !object.is_null() => match env.new_global_ref(&object) {
                Ok(global) => global,
                Err(_) => {
                    let _ = env.exception_clear();
                    let _ = env.call_method(&object, "release", "()V", &[]);
                    let _ = env.exception_clear();
                    free(handle);
                    return None;
                }
            },
            _ => {
                free(handle);
                return None;
            }
        };
        let stream = JavaStream {
            jvm: jvm.clone(),
            recorder,
            handle,
        };
        // Start now: the routed device and session are known only once recording.
        if stream.start().is_err() {
            return None; // Drop releases the recorder and frees the handle.
        }
        let device_id = stream.int("routedDeviceId");
        let session_id = stream.int("sessionId");
        Some((
            stream,
            OpenedStream {
                device_id,
                session_id,
                sample_rate: 16_000,
                channels: 1,
                exact_format: true,
            },
        ))
    }

    fn int(&self, method: &str) -> i32 {
        let Ok(mut env) = self.jvm.attach_current_thread() else {
            return 0;
        };
        let value = env
            .call_method(self.recorder.as_obj(), method, "()I", &[])
            .and_then(|value| value.i())
            .unwrap_or(0);
        if env.exception_check().unwrap_or(true) {
            let _ = env.exception_clear();
        }
        value
    }

    pub fn start(&self) -> Result<(), String> {
        let mut env = self.jvm.attach_current_thread().map_err(|e| e.to_string())?;
        let started = env
            .call_method(self.recorder.as_obj(), "start", "()Z", &[])
            .and_then(|value| value.z())
            .unwrap_or(false);
        if env.exception_check().unwrap_or(true) {
            let _ = env.exception_clear();
            return Err("AudioRecord start failed".to_string());
        }
        if started {
            Ok(())
        } else {
            Err("AudioRecord refused to start".to_string())
        }
    }

    pub fn stop(&self) -> Result<(), String> {
        let mut env = self.jvm.attach_current_thread().map_err(|e| e.to_string())?;
        let _ = env.call_method(self.recorder.as_obj(), "stop", "()V", &[]);
        if env.exception_check().unwrap_or(true) {
            let _ = env.exception_clear();
        }
        Ok(())
    }
}

impl Drop for JavaStream {
    fn drop(&mut self) {
        // Retire admission first. A callback already holding an Arc remains valid
        // even if Java's bounded reader join times out.
        FEEDS.lock().unwrap_or_else(|p| p.into_inner()).remove(&self.handle);
        if let Ok(mut env) = self.jvm.attach_current_thread() {
            let _ = env.call_method(self.recorder.as_obj(), "release", "()V", &[]);
            if env.exception_check().unwrap_or(true) {
                let _ = env.exception_clear();
            }
        }

    }
}

fn feed(handle: jlong) -> Option<Arc<Mutex<Feed>>> {
    FEEDS.lock().ok()?.get(&handle).cloned()
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BluetoothAudioRecord_nativeFeed(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    samples: JFloatArray,
    count: jint,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
    let Some(feed) = feed(handle) else {
        return;
    };
    let Ok(mut feed) = feed.lock() else {
        return;
    };
    let count = count.max(0).min(env.get_array_length(&samples).unwrap_or(0)) as usize;
    let mut scratch = std::mem::take(&mut feed.scratch);
    scratch.resize(count, 0.0);
    if env.get_float_array_region(&samples, 0, &mut scratch).is_ok() {
        (feed.data)(&scratch);
    }
    feed.scratch = scratch;
    }));
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BluetoothAudioRecord_nativeError(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    code: jint,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
    if let Some(feed) = feed(handle) {
        if let Ok(mut feed) = feed.lock() {
            (feed.error)(format!("AudioRecord read error {code}"));
        };
    }
    }));
}

#[cfg(test)]
mod lifetime_tests {
    use super::*;

    #[test]
    fn retired_feed_rejects_late_calls_but_keeps_admitted_callback_alive() {
        let handle = NEXT_FEED.fetch_add(1, Ordering::SeqCst);
        let owned = Arc::new(Mutex::new(Feed { data: Box::new(|_| {}), error: Box::new(|_| {}), scratch: vec![] }));
        let weak = Arc::downgrade(&owned);
        FEEDS.lock().unwrap_or_else(|p| p.into_inner()).insert(handle, owned);
        let admitted = feed(handle).unwrap();
        FEEDS.lock().unwrap_or_else(|p| p.into_inner()).remove(&handle);
        assert!(feed(handle).is_none());
        assert!(weak.upgrade().is_some());
        admitted.lock().unwrap().scratch.push(1.0);
        drop(admitted);
        assert!(weak.upgrade().is_none());
    }
}
