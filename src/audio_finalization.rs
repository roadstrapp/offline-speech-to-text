/// Offline decoders need a small amount of right context to reliably emit the
/// final tokens of an utterance. This is inference-only silence: callers stop
/// capture before invoking this helper, so the microphone is never kept open
/// while transcription runs.
const FINAL_DECODE_SILENCE_MS: usize = 500;
const MODEL_SAMPLE_RATE_HZ: usize = 16_000;

pub(crate) fn append_final_decode_silence(samples: &mut Vec<f32>) {
    if samples.is_empty() {
        return;
    }
    let silence_samples = MODEL_SAMPLE_RATE_HZ * FINAL_DECODE_SILENCE_MS / 1000;
    samples.resize(samples.len().saturating_add(silence_samples), 0.0);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preserves_audio_and_adds_bounded_right_context() {
        let original = vec![0.25, -0.5, 0.75];
        let mut samples = original.clone();

        append_final_decode_silence(&mut samples);

        assert_eq!(&samples[..original.len()], original.as_slice());
        assert_eq!(
            samples.len(),
            original.len() + MODEL_SAMPLE_RATE_HZ * FINAL_DECODE_SILENCE_MS / 1000
        );
        assert!(samples[original.len()..]
            .iter()
            .all(|sample| *sample == 0.0));
    }

    #[test]
    fn empty_capture_remains_empty() {
        let mut samples = Vec::new();
        append_final_decode_silence(&mut samples);
        assert!(samples.is_empty());
    }
}
