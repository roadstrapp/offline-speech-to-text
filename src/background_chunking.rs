//! Real-time-safe, lossless capture buffering.
//!
//! Offline models must receive one continuous utterance. Splitting on a short
//! pause changes the language context and lets each independent decode invent
//! an end-of-sentence boundary. Keep capture cheap and defer the complete
//! utterance to the engine until the user finishes speaking.

#[derive(Default)]
pub(crate) struct CaptureBuffer {
    samples: Vec<f32>,
    total_samples: u64,
}

impl CaptureBuffer {
    pub(crate) fn reset(&mut self) {
        self.samples.clear();
        self.total_samples = 0;
    }

    /// Appends one capture callback without copying the complete recording.
    pub(crate) fn push(&mut self, data: &[f32]) {
        self.samples.extend_from_slice(data);
        self.total_samples = self.total_samples.saturating_add(data.len() as u64);
    }

    pub(crate) fn take_tail(&mut self) -> Vec<f32> {
        std::mem::take(&mut self.samples)
    }

    pub(crate) fn has_audio(&self) -> bool {
        self.total_samples > 0
    }

    pub(crate) fn total_samples(&self) -> u64 {
        self.total_samples
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pauses_never_split_the_utterance() {
        let mut capture = CaptureBuffer::default();
        capture.push(&vec![0.5; 5 * 16_000]);
        capture.push(&vec![0.0; 2 * 16_000]);
        capture.push(&vec![0.5; 5 * 16_000]);
        assert_eq!(capture.take_tail().len(), 12 * 16_000);
    }

    #[test]
    fn take_tail_moves_the_exact_recording_without_a_clone() {
        let mut capture = CaptureBuffer::default();
        let input: Vec<f32> = (0..40 * 16_000).map(|index| index as f32).collect();
        capture.push(&input);
        assert_eq!(capture.total_samples(), input.len() as u64);
        assert_eq!(capture.take_tail(), input);
        assert!(capture.take_tail().is_empty());
    }
}
