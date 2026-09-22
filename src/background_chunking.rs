//! Real-time-safe capture buffering for background long-dictation decoding.

use crate::long_audio_chunking::Plan;

#[derive(Default)]
pub(crate) struct CaptureBuffer {
    samples: Vec<f32>,
    total_samples: u64,
    trailing_silence_samples: usize,
    speech_seen: bool,
}

impl CaptureBuffer {
    pub(crate) fn reset(&mut self) {
        self.samples.clear();
        self.total_samples = 0;
        self.trailing_silence_samples = 0;
        self.speech_seen = false;
    }

    /// Appends one capture callback and returns at most one completed chunk.
    /// Audio callbacks are far smaller than either model plan, so one split is
    /// sufficient and keeps this hot path bounded.
    pub(crate) fn push(
        &mut self,
        data: &[f32],
        is_speech: bool,
        plan: Option<Plan>,
    ) -> Option<Vec<f32>> {
        self.samples.extend_from_slice(data);
        self.total_samples = self.total_samples.saturating_add(data.len() as u64);
        if is_speech {
            self.trailing_silence_samples = 0;
            self.speech_seen = true;
        } else {
            self.trailing_silence_samples = self
                .trailing_silence_samples
                .saturating_add(data.len())
                .min(self.samples.len());
        }

        let plan = plan?;
        // Reserve the decoder's inference-only right context inside the
        // model window. Otherwise a hard-limit chunk plus its 500 ms tail can
        // become one full run followed by a wasteful silence-only run.
        let capture_limit = plan
            .max_run_samples()
            .saturating_sub(crate::audio_finalization::final_decode_silence_samples())
            .max(1);
        let take = if self.samples.len() >= capture_limit {
            plan.next_chunk_len_with_limit(&self.samples, capture_limit)
        } else if self.speech_seen
            && self.samples.len() >= plan.min_natural_split_samples()
            && self.trailing_silence_samples >= plan.boundary_silence_samples()
        {
            self.samples.len()
        } else {
            return None;
        };

        let remainder = self.samples.split_off(take);
        let chunk = std::mem::replace(&mut self.samples, remainder);
        self.trailing_silence_samples = self.trailing_silence_samples.min(self.samples.len());
        // Be conservative across a hard split: only the current callback's
        // classification is known to belong to the retained tail. A later
        // speech block re-arms natural-boundary sealing; otherwise the hard
        // maximum remains the safe fallback for quiet or ambiguous audio.
        self.speech_seen = !self.samples.is_empty() && is_speech;
        Some(chunk)
    }

    pub(crate) fn restore_front(&mut self, mut chunk: Vec<f32>) {
        chunk.append(&mut self.samples);
        self.samples = chunk;
        self.trailing_silence_samples = self.trailing_silence_samples.min(self.samples.len());
        self.speech_seen = true;
    }

    pub(crate) fn take_tail(&mut self) -> Vec<f32> {
        self.trailing_silence_samples = 0;
        self.speech_seen = false;
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
    fn conformer_capture_seals_after_a_sentence_pause() {
        let plan = Plan::for_conformer();
        let mut capture = CaptureBuffer::default();
        assert!(capture
            .push(&vec![0.5; 5 * 16_000], true, Some(plan))
            .is_none());
        let chunk = capture
            .push(
                &vec![0.0; plan.boundary_silence_samples()],
                false,
                Some(plan),
            )
            .expect("sentence pause seals a chunk");
        assert_eq!(chunk.len(), 5 * 16_000 + plan.boundary_silence_samples());
        assert!(capture.take_tail().is_empty());
    }

    #[test]
    fn whisper_does_not_turn_short_pauses_into_tiny_encoder_runs() {
        let plan = Plan::for_whisper();
        let mut capture = CaptureBuffer::default();
        assert!(capture
            .push(&vec![0.5; 10 * 16_000], true, Some(plan))
            .is_none());
        assert!(capture
            .push(
                &vec![0.0; plan.boundary_silence_samples()],
                false,
                Some(plan)
            )
            .is_none());
        assert_eq!(capture.total_samples(), 10 * 16_000 + 9_600);
    }

    #[test]
    fn hard_split_preserves_every_sample_and_order() {
        let plan = Plan::for_conformer();
        let mut capture = CaptureBuffer::default();
        let input: Vec<f32> = (0..plan.max_run_samples() + 123)
            .map(|index| index as f32)
            .collect();
        let first = capture
            .push(&input, true, Some(plan))
            .expect("hard limit seals a chunk");
        assert!(
            first.len() + crate::audio_finalization::final_decode_silence_samples()
                <= plan.max_run_samples()
        );
        let tail = capture.take_tail();
        let mut restored = first;
        restored.extend_from_slice(&tail);
        assert_eq!(restored, input);
    }

    #[test]
    fn unknown_model_plan_keeps_one_lossless_tail() {
        let mut capture = CaptureBuffer::default();
        let input = vec![0.25; 40 * 16_000];
        assert!(capture.push(&input, true, None).is_none());
        assert_eq!(capture.take_tail(), input);
    }

    #[test]
    fn low_level_audio_without_a_proven_speech_block_waits_for_the_hard_limit() {
        let plan = Plan::for_conformer();
        let mut capture = CaptureBuffer::default();
        assert!(capture
            .push(
                &vec![0.001; plan.min_natural_split_samples()],
                false,
                Some(plan)
            )
            .is_none());
        assert!(capture
            .push(
                &vec![0.0; plan.boundary_silence_samples()],
                false,
                Some(plan)
            )
            .is_none());
    }
}
