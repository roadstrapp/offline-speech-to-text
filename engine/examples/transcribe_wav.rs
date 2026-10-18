//! Host-side check of the engine against a real model:
//!
//!   cargo run --release --example transcribe_wav -- model.gguf audio.wav [language]
//!
//! `audio.wav` must be 16 kHz, mono, 16-bit PCM (`ffmpeg -i in.mp3 -ar 16000 -ac 1 out.wav`).

use std::path::Path;

use offlinestt::core::{SttEngine, SAMPLE_RATE};
use transcribe_cpp::CancelToken;

fn read_wav_16k_mono(path: &Path) -> Result<Vec<f32>, String> {
    let bytes = std::fs::read(path).map_err(|e| e.to_string())?;
    if bytes.len() < 44 || &bytes[0..4] != b"RIFF" || &bytes[8..12] != b"WAVE" {
        return Err("not a RIFF/WAVE file".into());
    }
    let mut at = 12;
    let (mut channels, mut rate, mut bits) = (0u16, 0u32, 0u16);
    while at + 8 <= bytes.len() {
        let id = &bytes[at..at + 4];
        let size = u32::from_le_bytes(bytes[at + 4..at + 8].try_into().unwrap()) as usize;
        let body = at + 8;
        if id == b"fmt " {
            channels = u16::from_le_bytes(bytes[body + 2..body + 4].try_into().unwrap());
            rate = u32::from_le_bytes(bytes[body + 4..body + 8].try_into().unwrap());
            bits = u16::from_le_bytes(bytes[body + 14..body + 16].try_into().unwrap());
        } else if id == b"data" {
            if (channels, rate, bits) != (1, SAMPLE_RATE as u32, 16) {
                return Err(format!("need 16 kHz mono 16-bit, got {rate} Hz, {channels} ch, {bits}-bit"));
            }
            let end = (body + size).min(bytes.len());
            return Ok(bytes[body..end]
                .chunks_exact(2)
                .map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0)
                .collect());
        }
        at = body + size + (size & 1);
    }
    Err("no data chunk".into())
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 3 {
        eprintln!("usage: transcribe_wav <model.gguf> <audio.wav> [language]");
        std::process::exit(2);
    }
    let pcm = read_wav_16k_mono(Path::new(&args[2])).unwrap_or_else(|e| {
        eprintln!("{e}");
        std::process::exit(2);
    });
    let started = std::time::Instant::now();
    let mut engine = SttEngine::load(Path::new(&args[1]), args.get(3).cloned(), 0, &CancelToken::new())
        .unwrap_or_else(|e| {
            eprintln!("{e}");
            std::process::exit(1);
        });
    let load_ms = started.elapsed().as_millis();
    match engine.transcribe(&pcm) {
        Ok(t) => println!("{}\t[{} ms audio, {} ms decode, {} ms load, lang {:?}]", t.text, t.audio_ms, t.decode_ms, load_ms, t.language),
        Err(e) => {
            eprintln!("{e}");
            std::process::exit(1);
        }
    }
}
