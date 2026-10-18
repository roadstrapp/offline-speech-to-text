//! Embeddable offline speech-to-text engine.
//!
//! A deliberately small facade over `transcribe-cpp` (ggml, GGUF models): load
//! one model, hand it one complete 16 kHz mono utterance, get text back. There
//! is no Activity, service, microphone or model catalog in here; the host app
//! owns recording, endpointing, model download and policy.
//!
//! * [`core`] is plain Rust and unit-testable on the host.
//! * [`threads`] picks an energy-conscious inference thread count.
//! * `jni_api` (Android only) exposes the engine to Kotlin through opaque handles.

pub mod core;
pub mod threads;

#[cfg(target_os = "android")]
mod jni_api;
