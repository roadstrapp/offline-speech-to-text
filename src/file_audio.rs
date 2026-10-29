//! Sliding audio storage for files. Consumed audio is discarded, except for the
//! exact overlap required by the existing long-audio reconciliation policy.
#[derive(Default)]
pub(crate) struct Buffer {
    samples: Vec<f32>,
}
impl Buffer {
    pub(crate) fn len(&self) -> usize { self.samples.len() }
    pub(crate) fn push(&mut self, samples: &[f32]) {
        self.samples.extend_from_slice(samples);
    }
    pub(crate) fn take_ready(&mut self, limit: usize) -> Option<Vec<f32>> {
        let range = crate::long_audio::complete_window(&self.samples, 0, limit)?;
        let piece = self.samples[..range.end].to_vec();
        let consumed = range
            .end
            .saturating_sub(crate::long_audio::overlap_samples(limit));
        self.samples.drain(..consumed);
        Some(piece)
    }
    pub(crate) fn finish(&mut self) -> Vec<f32> {
        std::mem::take(&mut self.samples)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn hours_of_audio_have_bounded_storage_and_no_missing_boundary_samples() {
        let limit = 60 * 16000;
        let overlap = crate::long_audio::overlap_samples(limit);
        let mut buffer = Buffer::default();
        let mut consumed = 0usize;
        let mut windows = 0;
        for _ in 0..3600 {
            buffer.push(&vec![0.1; 160000]);
            while let Some(window) = buffer.take_ready(limit) {
                assert!(window.len() <= limit);
                assert!(window.len() > overlap);
                consumed += window.len() - overlap;
                windows += 1;
            }
            assert!(buffer.samples.len() <= limit);
            assert!(buffer.samples.capacity() <= 2 * (limit + 160000));
        }
        let tail = buffer.finish();
        assert_eq!(consumed + tail.len(), 3600 * 160000);
        assert!(windows > 100);
        assert!(buffer.samples.is_empty());
    }
    #[test]
    fn short_file_is_one_unchanged_utterance() {
        let mut buffer = Buffer::default();
        buffer.push(&[0.2, 0.3, 0.4]);
        assert!(buffer.take_ready(960000).is_none());
        assert_eq!(buffer.finish(), vec![0.2, 0.3, 0.4]);
    }
}
