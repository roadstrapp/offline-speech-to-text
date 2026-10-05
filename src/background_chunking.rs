//! Real-time-safe, lossless capture buffering.
//!
//! Offline models must receive one continuous utterance. Splitting on a short
//! pause changes the language context and lets each independent decode invent
//! an end-of-sentence boundary. Keep capture cheap and defer the complete
//! utterance intact. Only immutable windows of genuinely long recordings may
//! be copied by the background worker; the capture callback never decodes.

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

    /// Only the bounded prefix copy needs the capture lock. Quiet-point search,
    /// padding and decoding run after it is released. One additional sample
    /// preserves complete_window's strict non-final-window requirement.
    pub(crate) fn window_snapshot(&self, start: usize, limit: usize) -> Option<Vec<f32>> {
        let end = start.checked_add(limit)?.checked_add(1)?;
        self.samples.get(start..end).map(<[f32]>::to_vec)
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

    #[test]
    fn snapshot_is_bounded_and_never_consumes_original_samples() {
        let mut capture = CaptureBuffer::default();
        let original: Vec<f32> = (0..30_001).map(|i| i as f32).collect();
        capture.push(&original);
        assert_eq!(
            capture.window_snapshot(10_000, 20_000).unwrap(),
            original[10_000..]
        );
        assert!(capture.window_snapshot(10_001, 20_000).is_none());
        assert!(capture.window_snapshot(usize::MAX, 20_000).is_none());
        assert_eq!(capture.total_samples(), original.len() as u64);
        assert_eq!(capture.take_tail(), original);
    }
}
