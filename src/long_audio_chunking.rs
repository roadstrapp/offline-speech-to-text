//! Bounded, quiet-point chunk selection for long model inputs.

const SAMPLE_RATE: usize = 16_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Plan {
    max_run_samples: usize,
    split_search_samples: usize,
    min_natural_split_samples: usize,
    boundary_silence_samples: usize,
}

impl Plan {
    /// Conformer attention grows superlinearly with input duration. Short
    /// runs materially reduce latency without changing the <= 11 s benchmark
    /// path. This is an optimization, not a promise that every model/device
    /// can finish a ten-minute recording before the fail-closed watchdog.
    pub const fn for_conformer() -> Self {
        Self {
            max_run_samples: 15 * SAMPLE_RATE,
            split_search_samples: 3 * SAMPLE_RATE,
            min_natural_split_samples: 5 * SAMPLE_RATE,
            boundary_silence_samples: 600 * SAMPLE_RATE / 1000,
        }
    }

    /// Whisper encoders use 30-second windows internally; keep that alignment
    /// instead of doubling padded encoder work with 15-second runs.
    pub const fn for_whisper() -> Self {
        Self {
            max_run_samples: 30 * SAMPLE_RATE,
            split_search_samples: 10 * SAMPLE_RATE,
            // Whisper pads/encodes a 30-second window. Waiting for a longer
            // phrase before a silence split avoids turning one window into a
            // series of expensive five-second runs.
            min_natural_split_samples: 20 * SAMPLE_RATE,
            boundary_silence_samples: 600 * SAMPLE_RATE / 1000,
        }
    }

    pub const fn max_run_samples(self) -> usize {
        self.max_run_samples
    }

    pub const fn min_natural_split_samples(self) -> usize {
        self.min_natural_split_samples
    }

    pub const fn boundary_silence_samples(self) -> usize {
        self.boundary_silence_samples
    }

    pub fn next_chunk_len(self, samples: &[f32]) -> usize {
        self.next_chunk_len_with_limit(samples, self.max_run_samples)
    }

    pub(crate) fn next_chunk_len_with_limit(self, samples: &[f32], limit: usize) -> usize {
        let limit = limit.min(self.max_run_samples).max(1);
        if samples.len() <= limit {
            samples.len()
        } else {
            crate::audio::find_quietest_split(
                samples,
                limit.saturating_sub(self.split_search_samples),
                limit,
            )
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn conformer_plan_prioritizes_short_sentence_sized_work() {
        let plan = Plan::for_conformer();
        assert_eq!(plan.min_natural_split_samples(), 5 * SAMPLE_RATE);
        assert_eq!(plan.max_run_samples(), 15 * SAMPLE_RATE);
        assert!(plan.boundary_silence_samples() > 0);
    }

    #[test]
    fn whisper_plan_preserves_the_thirty_second_encoder_window() {
        let plan = Plan::for_whisper();
        assert_eq!(plan.min_natural_split_samples(), 20 * SAMPLE_RATE);
        assert_eq!(plan.max_run_samples(), 30 * SAMPLE_RATE);
        assert!(plan.min_natural_split_samples() > Plan::for_conformer().max_run_samples());
    }
}
