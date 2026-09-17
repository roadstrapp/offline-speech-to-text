//! Bounded, quiet-point chunk selection for long model inputs.

#[derive(Clone, Copy)]
pub struct Plan {
    max_run_samples: usize,
    split_search_samples: usize,
}

impl Plan {
    /// Conformer attention grows superlinearly with input duration. Short
    /// runs materially reduce latency without changing the <= 11 s benchmark
    /// path. This is an optimization, not a promise that every model/device
    /// can finish a ten-minute recording before the fail-closed watchdog.
    pub const fn for_conformer() -> Self {
        Self {
            max_run_samples: 15 * 16_000,
            split_search_samples: 3 * 16_000,
        }
    }

    /// Whisper encoders use 30-second windows internally; keep that alignment
    /// instead of doubling padded encoder work with 15-second runs.
    pub const fn for_whisper() -> Self {
        Self {
            max_run_samples: 30 * 16_000,
            split_search_samples: 10 * 16_000,
        }
    }

    pub const fn max_run_samples(self) -> usize {
        self.max_run_samples
    }

    pub fn next_chunk_len(self, samples: &[f32]) -> usize {
        if samples.len() <= self.max_run_samples {
            samples.len()
        } else {
            crate::audio::find_quietest_split(
                samples,
                self.max_run_samples - self.split_search_samples,
                self.max_run_samples,
            )
        }
    }
}
