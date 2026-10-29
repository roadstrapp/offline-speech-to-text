//! The microphone input stream behind every OSTT-owned capture.
//!
//! Ordinary capture keeps cpal's default AAudio input (voice-recognition
//! preset, phone microphone). When the optional Bluetooth route is up, the
//! stream is opened through AAudio directly with the VOICE_COMMUNICATION
//! input preset and, when Java found one, the headset's input device id:
//! Android's input policy does not move a VOICE_RECOGNITION capture to an LE
//! Audio microphone just because it is the communication device, and LE
//! Audio hearing aids only open their microphones for the call-like
//! (communication) use case. AAudio is loaded at run time, as oboe does, so
//! the library keeps its link-time API level; the preset and session entry
//! points (API 28) are optional. The opened stream is handed to the caller's
//! `accept` check (Java verifies the device it really opened on); if it is not
//! 16 kHz mono float, or not on a Bluetooth input, the same callbacks fall
//! back to the default microphone and the caller releases the route.

use cpal::traits::{DeviceTrait, StreamTrait};

const SAMPLE_RATE: u32 = 16_000;

pub enum CaptureStream {
    Default(cpal::Stream),
    #[cfg(target_os = "android")]
    Device(DeviceStream),
    /// Java AudioRecord on a Bluetooth input (see bt_audio_record.rs).
    Java(crate::bt_audio_record::JavaStream),
}

impl CaptureStream {
    pub fn play(&self) -> Result<(), String> {
        match self {
            CaptureStream::Default(stream) => stream.play().map_err(|e| e.to_string()),
            #[cfg(target_os = "android")]
            CaptureStream::Device(stream) => stream.start(),
            CaptureStream::Java(stream) => stream.start(),
        }
    }

    /// Suspend capture. AAudio input streams have no pause state, so an
    /// explicitly routed stream is stopped and restarted by `play`.
    pub fn pause(&self) -> Result<(), String> {
        match self {
            CaptureStream::Default(stream) => stream.pause().map_err(|e| e.to_string()),
            #[cfg(target_os = "android")]
            CaptureStream::Device(stream) => stream.stop(),
            CaptureStream::Java(stream) => stream.stop(),
        }
    }
}

/// Open a 16 kHz mono input. `route` carries the routed Bluetooth input id
/// (0 = follow the communication device) and the check that accepts the
/// opened stream; the returned flag says whether the routed stream is in use.
pub fn open_input<D, E, A>(
    default_device: &cpal::Device,
    route: Option<(i32, A)>,
    data: D,
    error: E,
) -> Result<(CaptureStream, bool), String>
where
    D: FnMut(&[f32]) + Send + 'static,
    E: FnMut(String) + Send + 'static,
    A: FnOnce(&crate::bluetooth_mic::OpenedStream) -> bool,
{
    #[cfg(target_os = "android")]
    let (data, error) = match route {
        Some((device_id, accept)) => match device::open(device_id, accept, data, error) {
            Ok(stream) => return Ok((CaptureStream::Device(stream), true)),
            Err(callbacks) => callbacks,
        },
        None => (data, error),
    };
    #[cfg(not(target_os = "android"))]
    let _ = route;

    let mut data = data;
    let mut error = error;
    let config = cpal::StreamConfig {
        channels: 1,
        sample_rate: cpal::SampleRate(SAMPLE_RATE),
        buffer_size: cpal::BufferSize::Default,
    };
    default_device
        .build_input_stream(
            &config,
            move |samples: &[f32], _: &_| data(samples),
            move |e| error(e.to_string()),
            None,
        )
        .map(|stream| (CaptureStream::Default(stream), false))
        .map_err(|e| e.to_string())
}

#[cfg(target_os = "android")]
mod device {
    use std::ffi::c_void;
    use std::panic::{catch_unwind, AssertUnwindSafe};

    use once_cell::sync::Lazy;

    // aaudio/AAudio.h (API 26) values.
    const AAUDIO_OK: i32 = 0;
    const AAUDIO_DIRECTION_INPUT: i32 = 1;
    const AAUDIO_FORMAT_PCM_FLOAT: i32 = 2;
    const AAUDIO_SHARING_MODE_SHARED: i32 = 1;
    const AAUDIO_CALLBACK_RESULT_CONTINUE: i32 = 0;
    const AAUDIO_CALLBACK_RESULT_STOP: i32 = 1;
    const AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION: i32 = 7;
    const AAUDIO_SESSION_ID_ALLOCATE: i32 = 0;

    type Builder = c_void;
    type Stream = c_void;
    type DataCallback = unsafe extern "C" fn(*mut Stream, *mut c_void, *mut c_void, i32) -> i32;
    type ErrorCallback = unsafe extern "C" fn(*mut Stream, *mut c_void, i32);

    struct Api {
        create_builder: unsafe extern "C" fn(*mut *mut Builder) -> i32,
        set_device_id: unsafe extern "C" fn(*mut Builder, i32),
        set_direction: unsafe extern "C" fn(*mut Builder, i32),
        set_sample_rate: unsafe extern "C" fn(*mut Builder, i32),
        set_channel_count: unsafe extern "C" fn(*mut Builder, i32),
        set_format: unsafe extern "C" fn(*mut Builder, i32),
        set_sharing_mode: unsafe extern "C" fn(*mut Builder, i32),
        set_data_callback: unsafe extern "C" fn(*mut Builder, DataCallback, *mut c_void),
        set_error_callback: unsafe extern "C" fn(*mut Builder, ErrorCallback, *mut c_void),
        open_stream: unsafe extern "C" fn(*mut Builder, *mut *mut Stream) -> i32,
        delete_builder: unsafe extern "C" fn(*mut Builder) -> i32,
        request_start: unsafe extern "C" fn(*mut Stream) -> i32,
        request_stop: unsafe extern "C" fn(*mut Stream) -> i32,
        close: unsafe extern "C" fn(*mut Stream) -> i32,
        device_id: unsafe extern "C" fn(*mut Stream) -> i32,
        sample_rate: unsafe extern "C" fn(*mut Stream) -> i32,
        channel_count: unsafe extern "C" fn(*mut Stream) -> i32,
        format: unsafe extern "C" fn(*mut Stream) -> i32,
        // API 28; absent on Android 8.x, where the route is never requested.
        set_input_preset: Option<unsafe extern "C" fn(*mut Builder, i32)>,
        set_session_id: Option<unsafe extern "C" fn(*mut Builder, i32)>,
        session_id: Option<unsafe extern "C" fn(*mut Stream) -> i32>,
    }

    // The library stays loaded for the life of the process.
    static API: Lazy<Option<Api>> = Lazy::new(|| unsafe { load() });

    unsafe fn load() -> Option<Api> {
        let library = libc::dlopen(c"libaaudio.so".as_ptr(), libc::RTLD_NOW);
        if library.is_null() {
            return None;
        }
        macro_rules! optional {
            ($name:literal) => {{
                let address = libc::dlsym(library, $name.as_ptr());
                if address.is_null() {
                    None
                } else {
                    Some(std::mem::transmute(address))
                }
            }};
        }
        macro_rules! symbol {
            ($name:literal) => {{
                let address = libc::dlsym(library, $name.as_ptr());
                if address.is_null() {
                    return None;
                }
                std::mem::transmute(address)
            }};
        }
        Some(Api {
            create_builder: symbol!(c"AAudio_createStreamBuilder"),
            set_device_id: symbol!(c"AAudioStreamBuilder_setDeviceId"),
            set_direction: symbol!(c"AAudioStreamBuilder_setDirection"),
            set_sample_rate: symbol!(c"AAudioStreamBuilder_setSampleRate"),
            set_channel_count: symbol!(c"AAudioStreamBuilder_setChannelCount"),
            set_format: symbol!(c"AAudioStreamBuilder_setFormat"),
            set_sharing_mode: symbol!(c"AAudioStreamBuilder_setSharingMode"),
            set_data_callback: symbol!(c"AAudioStreamBuilder_setDataCallback"),
            set_error_callback: symbol!(c"AAudioStreamBuilder_setErrorCallback"),
            open_stream: symbol!(c"AAudioStreamBuilder_openStream"),
            delete_builder: symbol!(c"AAudioStreamBuilder_delete"),
            request_start: symbol!(c"AAudioStream_requestStart"),
            request_stop: symbol!(c"AAudioStream_requestStop"),
            close: symbol!(c"AAudioStream_close"),
            device_id: symbol!(c"AAudioStream_getDeviceId"),
            sample_rate: symbol!(c"AAudioStream_getSampleRate"),
            channel_count: symbol!(c"AAudioStream_getChannelCount"),
            format: symbol!(c"AAudioStream_getFormat"),
            set_input_preset: optional!(c"AAudioStreamBuilder_setInputPreset"),
            set_session_id: optional!(c"AAudioStreamBuilder_setSessionId"),
            session_id: optional!(c"AAudioStream_getSessionId"),
        })
    }

    /// Callback state owned by the stream. The data and error callbacks touch
    /// disjoint fields, and AAudio never runs either one concurrently with itself.
    struct Callbacks<D, E> {
        data: D,
        error: E,
    }

    unsafe extern "C" fn on_data<D, E>(
        _stream: *mut Stream,
        user: *mut c_void,
        audio: *mut c_void,
        frames: i32,
    ) -> i32
    where
        D: FnMut(&[f32]),
    {
        if user.is_null() || audio.is_null() || frames <= 0 {
            return AAUDIO_CALLBACK_RESULT_CONTINUE;
        }
        let callbacks = user as *mut Callbacks<D, E>;
        // SAFETY: the stream was verified as mono f32, so AAudio passes
        // `frames` samples; `user` outlives the stream (freed after close).
        let samples = std::slice::from_raw_parts(audio as *const f32, frames as usize);
        let data = &mut *std::ptr::addr_of_mut!((*callbacks).data);
        match catch_unwind(AssertUnwindSafe(|| data(samples))) {
            Ok(()) => AAUDIO_CALLBACK_RESULT_CONTINUE,
            Err(_) => AAUDIO_CALLBACK_RESULT_STOP,
        }
    }

    unsafe extern "C" fn on_error<D, E>(_stream: *mut Stream, user: *mut c_void, code: i32)
    where
        E: FnMut(String),
    {
        if user.is_null() {
            return;
        }
        let callbacks = user as *mut Callbacks<D, E>;
        let error = &mut *std::ptr::addr_of_mut!((*callbacks).error);
        let _ = catch_unwind(AssertUnwindSafe(|| error(format!("AAudio error {code}"))));
    }

    pub struct DeviceStream {
        stream: *mut Stream,
        user: *mut c_void,
        free_user: unsafe fn(*mut c_void),
    }

    unsafe fn free_callbacks<D, E>(user: *mut c_void) {
        drop(Box::from_raw(user as *mut Callbacks<D, E>));
    }

    impl DeviceStream {
        pub fn start(&self) -> Result<(), String> {
            result(unsafe { (api().request_start)(self.stream) })
        }

        pub fn stop(&self) -> Result<(), String> {
            result(unsafe { (api().request_stop)(self.stream) })
        }
    }

    impl Drop for DeviceStream {
        fn drop(&mut self) {
            unsafe {
                let api = api();
                (api.request_stop)(self.stream);
                // Close returns after the last callback; only then free its state.
                (api.close)(self.stream);
                (self.free_user)(self.user);
            }
        }
    }

    fn api() -> &'static Api {
        API.as_ref().expect("DeviceStream exists only after AAudio loaded")
    }

    fn result(code: i32) -> Result<(), String> {
        if code == AAUDIO_OK {
            Ok(())
        } else {
            Err(format!("AAudio error {code}"))
        }
    }

    /// Open a communication-preset stream on `device_id` (0 = the route's
    /// choice) and let `accept` judge it, or hand both callbacks back for the
    /// default input.
    pub fn open<D, E, A>(
        device_id: i32,
        accept: A,
        data: D,
        error: E,
    ) -> Result<DeviceStream, (D, E)>
    where
        D: FnMut(&[f32]) + Send + 'static,
        E: FnMut(String) + Send + 'static,
        A: FnOnce(&crate::bluetooth_mic::OpenedStream) -> bool,
    {
        let Some(api) = API.as_ref() else {
            return Err((data, error));
        };
        let user = Box::into_raw(Box::new(Callbacks { data, error }));
        let reclaim = |user: *mut Callbacks<D, E>| {
            // SAFETY: no stream holds `user` any more (never opened or closed).
            let callbacks = unsafe { Box::from_raw(user) };
            Err((callbacks.data, callbacks.error))
        };
        unsafe {
            let mut builder: *mut Builder = std::ptr::null_mut();
            if (api.create_builder)(&mut builder) != AAUDIO_OK || builder.is_null() {
                return reclaim(user);
            }
            (api.set_direction)(builder, AAUDIO_DIRECTION_INPUT);
            if device_id > 0 {
                (api.set_device_id)(builder, device_id);
            }
            if let Some(set_input_preset) = api.set_input_preset {
                set_input_preset(builder, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);
            }
            if let Some(set_session_id) = api.set_session_id {
                // A session id lets Java find this stream among the
                // recording configurations and log where Android routes it.
                set_session_id(builder, AAUDIO_SESSION_ID_ALLOCATE);
            }
            (api.set_sample_rate)(builder, super::SAMPLE_RATE as i32);
            (api.set_channel_count)(builder, 1);
            (api.set_format)(builder, AAUDIO_FORMAT_PCM_FLOAT);
            (api.set_sharing_mode)(builder, AAUDIO_SHARING_MODE_SHARED);
            (api.set_data_callback)(builder, on_data::<D, E>, user as *mut c_void);
            (api.set_error_callback)(builder, on_error::<D, E>, user as *mut c_void);
            let mut stream: *mut Stream = std::ptr::null_mut();
            let opened = (api.open_stream)(builder, &mut stream);
            (api.delete_builder)(builder);
            if opened != AAUDIO_OK || stream.is_null() {
                return reclaim(user);
            }
            // Accept only exactly what the callbacks expect, on a Bluetooth input.
            let opened = crate::bluetooth_mic::OpenedStream {
                device_id: (api.device_id)(stream),
                session_id: api.session_id.map(|get| get(stream)).unwrap_or(-1),
                sample_rate: (api.sample_rate)(stream),
                channels: (api.channel_count)(stream),
                exact_format: (api.sample_rate)(stream) == super::SAMPLE_RATE as i32
                    && (api.channel_count)(stream) == 1
                    && (api.format)(stream) == AAUDIO_FORMAT_PCM_FLOAT,
            };
            if !opened.exact_format || !accept(&opened) {
                // Never started, so no callback can be running.
                (api.close)(stream);
                return reclaim(user);
            }
            Ok(DeviceStream {
                stream,
                user: user as *mut c_void,
                free_user: free_callbacks::<D, E>,
            })
        }
    }
}

#[cfg(target_os = "android")]
pub use device::DeviceStream;
