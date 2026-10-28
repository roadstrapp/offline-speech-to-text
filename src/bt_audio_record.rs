//! Bluetooth capture through Java `AudioRecord` (`BluetoothAudioRecord.java`).
//!
//! LE Audio hearing aids delivered only digital zeros to an AAudio stream on
//! their input (float, voice-communication preset, explicit device), while an
//! `AudioRecord` with PCM16, the VOICE_COMMUNICATION source and the input as
//! preferred device records them. The Java reader thread converts each block
//! to float and calls `nativeFeed` with an opaque handle to the capture
//! callbacks, which therefore see the same samples as from any other stream.
//! The handle is freed only after Java released the recorder, which joins
//! its reader thread, so no feed can arrive afterwards.

use std::sync::{Arc, Mutex};

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
    handle: *mut Mutex<Feed>,
}

unsafe impl Send for JavaStream {}

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
        let handle = Box::into_raw(Box::new(Mutex::new(Feed {
            data,
            error,
            scratch: Vec::new(),
        })));
        let free = |handle: *mut Mutex<Feed>| {
            // SAFETY: no Java recorder holds the handle (none was created or it was released).
            drop(unsafe { Box::from_raw(handle) });
        };
        let recorder = env
            .call_static_method(
                route.class(),
                "openRecorder",
                "(JJ)Ldev/notune/transcribe/BluetoothAudioRecord;",
                &[JValue::Long(route.token()), JValue::Long(handle as jlong)],
            )
            .and_then(|value| value.l());
        if env.exception_check().unwrap_or(true) {
            let _ = env.exception_clear();
            free(handle);
            return None;
        }
        let recorder = match recorder {
            Ok(object) if !object.is_null() => match env.new_global_ref(object) {
                Ok(global) => global,
                Err(_) => {
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
        if let Ok(mut env) = self.jvm.attach_current_thread() {
            let _ = env.call_method(self.recorder.as_obj(), "release", "()V", &[]);
            if env.exception_check().unwrap_or(true) {
                let _ = env.exception_clear();
            }
        }
        // SAFETY: release() joined the reader thread; nothing can feed any more.
        drop(unsafe { Box::from_raw(self.handle) });
    }
}

fn feed(handle: jlong) -> Option<&'static Mutex<Feed>> {
    if handle == 0 {
        return None;
    }
    // SAFETY: the handle lives until JavaStream::drop, after the reader thread ended.
    Some(unsafe { &*(handle as *const Mutex<Feed>) })
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BluetoothAudioRecord_nativeFeed(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    samples: JFloatArray,
    count: jint,
) {
    let Some(feed) = feed(handle) else {
        return;
    };
    let Ok(mut feed) = feed.lock() else {
        return;
    };
    let count = count.max(0) as usize;
    let mut scratch = std::mem::take(&mut feed.scratch);
    scratch.resize(count, 0.0);
    if env.get_float_array_region(&samples, 0, &mut scratch).is_ok() {
        (feed.data)(&scratch);
    }
    feed.scratch = scratch;
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_BluetoothAudioRecord_nativeError(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    code: jint,
) {
    if let Some(Ok(mut feed)) = feed(handle).map(|feed| feed.lock()) {
        (feed.error)(format!("AudioRecord read error {code}"));
    }
}
