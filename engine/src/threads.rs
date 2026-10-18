//! Energy-conscious inference thread count.
//!
//! Phone SoCs pair fast cores with slow efficiency cores, and ggml synchronizes
//! all threads after every operation, so one thread on a slow core stalls the
//! whole pool. Cores are classified by their maximum frequency: within 70% of
//! the fastest counts as fast. The count is capped at two because a benchmark
//! on a Pixel 6a showed four threads saving ~0.1 s on an 11 s utterance for
//! ~41% more process CPU time.

/// Upper bound on the automatically chosen thread count.
pub const MAX_AUTO_THREADS: usize = 2;

/// Threads to use given each core's maximum frequency in kHz (any order).
pub fn threads_for_frequencies(max_khz: &[u64]) -> usize {
    let Some(&fastest) = max_khz.iter().max() else {
        return fallback_threads();
    };
    if fastest == 0 {
        return fallback_threads();
    }
    let fast = max_khz.iter().filter(|&&f| f * 10 >= fastest * 7).count();
    fast.clamp(1, MAX_AUTO_THREADS)
}

fn fallback_threads() -> usize {
    std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(MAX_AUTO_THREADS)
        .clamp(1, MAX_AUTO_THREADS)
}

/// Reads per-core maximum frequencies from sysfs; empty when unreadable.
pub fn read_core_frequencies() -> Vec<u64> {
    let mut frequencies = Vec::new();
    for index in 0..64 {
        let path = format!("/sys/devices/system/cpu/cpu{index}/cpufreq/cpuinfo_max_freq");
        let Ok(text) = std::fs::read_to_string(path) else { break };
        let Ok(khz) = text.trim().parse::<u64>() else { break };
        frequencies.push(khz);
    }
    frequencies
}

/// Automatic thread count for this device.
pub fn auto_threads() -> usize {
    threads_for_frequencies(&read_core_frequencies())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_only_performance_cores_and_caps_at_two() {
        // 4 efficiency cores, 3 mid cores, 1 prime core: 4 within 70% of the prime.
        let cores = [1_800_000, 1_800_000, 1_800_000, 1_800_000, 2_400_000, 2_400_000, 2_400_000, 3_000_000];
        assert_eq!(threads_for_frequencies(&cores), 2);
    }

    #[test]
    fn a_single_fast_core_means_one_thread() {
        let cores = [1_000_000, 1_000_000, 1_000_000, 3_000_000];
        assert_eq!(threads_for_frequencies(&cores), 1);
    }

    #[test]
    fn unreadable_frequencies_fall_back_without_exceeding_the_cap() {
        let threads = threads_for_frequencies(&[]);
        assert!((1..=MAX_AUTO_THREADS).contains(&threads));
        assert_eq!(threads_for_frequencies(&[0, 0]), threads);
    }
}
