//! Bounded long-audio windows with context-preserving transcript stitching.
//!
//! Whisper owns its long-input windowing inside the native runtime. Other
//! offline encoders can build attention graphs proportional to the complete
//! input, so very long recordings need an app-level bound. Windows overlap:
//! when the same words appear at the end of one result and the beginning of
//! the next, the later hypothesis replaces that boundary. This keeps the
//! decoder's right context instead of preserving punctuation invented only
//! because an intermediate window ended.

use std::ops::Range;

pub(crate) const SAMPLE_RATE: usize = 16_000;
pub(crate) const DEFAULT_MAX_WINDOW_SAMPLES: usize = 60 * SAMPLE_RATE;
pub(crate) const MIN_WINDOW_SAMPLES: usize = 5 * SAMPLE_RATE;
const SPLIT_SEARCH_SAMPLES: usize = 10 * SAMPLE_RATE;
const OVERLAP_SAMPLES: usize = 3 * SAMPLE_RATE;
const MAX_OVERLAP_WORDS: usize = 24;
const MAX_LEADING_FALLBACK_WORDS: usize = 4;

/// Only a window already forced by the existing long-input bound is immutable.
/// Future samples cannot change its quiet-point search. A pause alone never
/// qualifies, and the final window must always wait for Stop.
pub(crate) fn complete_window(
    samples: &[f32],
    start: usize,
    max_window_samples: usize,
) -> Option<Range<usize>> {
    let limit = max_window_samples.max(1);
    if samples.len().saturating_sub(start) <= limit {
        return None;
    }
    let overlap = overlap_samples(limit);
    let hard_end = start.checked_add(limit)?;
    let search_start = hard_end
        .saturating_sub(SPLIT_SEARCH_SAMPLES.min(limit / 3))
        .max(start + 1);
    let split = crate::audio::find_quietest_split(samples, search_start, hard_end)
        .clamp(start + overlap + 1, hard_end);
    Some(start..split)
}

pub(crate) fn overlap_samples(limit: usize) -> usize {
    OVERLAP_SAMPLES.min(limit.max(1) / 4)
}

pub(crate) fn windows(samples: &[f32], max_window_samples: usize) -> Vec<Range<usize>> {
    if samples.is_empty() {
        return Vec::new();
    }
    let max_window_samples = max_window_samples.max(1);
    let overlap_samples = overlap_samples(max_window_samples);
    if samples.len() <= max_window_samples {
        return vec![0..samples.len()];
    }

    let mut result = Vec::new();
    let mut start = 0usize;
    while let Some(window) = complete_window(samples, start, max_window_samples) {
        start = window.end.saturating_sub(overlap_samples);
        result.push(window);
    }
    result.push(start..samples.len());
    result
}

#[derive(Debug)]
struct Word<'a> {
    start: usize,
    normalized: String,
    raw: &'a str,
}

fn words(text: &str) -> Vec<Word<'_>> {
    let mut result = Vec::new();
    let mut start = None;
    for (index, character) in text.char_indices() {
        if character.is_whitespace() {
            if let Some(word_start) = start.take() {
                push_word(&mut result, text, word_start, index);
            }
        } else if start.is_none() {
            start = Some(index);
        }
    }
    if let Some(word_start) = start {
        push_word(&mut result, text, word_start, text.len());
    }
    result
}

fn push_word<'a>(result: &mut Vec<Word<'a>>, text: &'a str, start: usize, end: usize) {
    let raw = &text[start..end];
    let normalized: String = raw
        .chars()
        .filter(|character| character.is_alphanumeric())
        .flat_map(char::to_lowercase)
        .collect();
    if !normalized.is_empty() {
        result.push(Word {
            start,
            normalized,
            raw,
        });
    }
}

/// Merge two overlapping ASR hypotheses without cutting at an audio sample.
/// A bounded edit-distance search tolerates a wrong or spurious boundary word.
/// With no trustworthy alignment, preserve both results rather than silently
/// dropping speech.
pub(crate) fn stitch(previous: &str, next: &str) -> String {
    let previous = previous.trim();
    let next = next.trim();
    if previous.is_empty() {
        return next.to_string();
    }
    if next.is_empty() {
        return previous.to_string();
    }

    let previous_words = words(previous);
    let next_words = words(next);
    let previous_start = previous_words.len().saturating_sub(MAX_OVERLAP_WORDS);
    let next_end = next_words.len().min(MAX_OVERLAP_WORDS);
    let mut best: Option<(i32, usize, usize)> = None;
    for left_start in previous_start..previous_words.len() {
        let left = &previous_words[left_start..];
        if left.len() < 3 {
            continue;
        }
        for right_start in 0..=next_end.min(MAX_LEADING_FALLBACK_WORDS) {
            let min_right = left.len().saturating_sub(2).max(3);
            let max_right = (left.len() + 2).min(next_end.saturating_sub(right_start));
            for right_len in min_right..=max_right {
                let right = &next_words[right_start..right_start + right_len];
                let distance = edit_distance(left, right);
                let max_len = left.len().max(right.len());
                let exact = exact_matches(left, right);
                if exact < 2 || distance * 3 > max_len {
                    continue;
                }
                let score = exact as i32 * 10 - distance as i32 * 6
                    + left.len().min(right.len()) as i32
                    - right_start as i32;
                if best.map_or(true, |current| score > current.0) {
                    best = Some((score, left_start, right_start));
                }
            }
        }
    }

    let Some((_, left_start, right_start)) = best else {
        return format!("{previous} {next}");
    };
    let replaced = &previous_words[left_start];
    let prefix = previous[..replaced.start].trim_end();
    let contextual_start = next_words[right_start].start;
    let contextual = match_case(next[contextual_start..].trim_start(), replaced.raw);
    if prefix.is_empty() {
        contextual
    } else {
        format!("{prefix} {contextual}")
    }
}

fn edit_distance(left: &[Word<'_>], right: &[Word<'_>]) -> usize {
    let mut previous: Vec<usize> = (0..=right.len()).collect();
    let mut current = vec![0; right.len() + 1];
    for (left_index, left_word) in left.iter().enumerate() {
        current[0] = left_index + 1;
        for (right_index, right_word) in right.iter().enumerate() {
            let substitution = usize::from(left_word.normalized != right_word.normalized);
            current[right_index + 1] = (previous[right_index + 1] + 1)
                .min(current[right_index] + 1)
                .min(previous[right_index] + substitution);
        }
        std::mem::swap(&mut previous, &mut current);
    }
    previous[right.len()]
}

fn exact_matches(left: &[Word<'_>], right: &[Word<'_>]) -> usize {
    // The length difference is at most two. Count aligned matches at the best
    // small offset so one inserted leading token does not erase the evidence.
    let mut best = 0;
    for left_offset in 0..=left.len().min(2) {
        for right_offset in 0..=right.len().min(2) {
            let matches = left[left_offset..]
                .iter()
                .zip(&right[right_offset..])
                .filter(|(a, b)| a.normalized == b.normalized)
                .count();
            best = best.max(matches);
        }
    }
    best
}

fn match_case(next: &str, replaced: &str) -> String {
    let Some(next_first) = next.chars().next() else {
        return String::new();
    };
    let Some(replaced_first) = replaced.chars().find(|character| character.is_alphabetic()) else {
        return next.to_string();
    };
    if replaced_first.is_lowercase() && next_first.is_uppercase() {
        let mut result = String::new();
        result.extend(next_first.to_lowercase());
        result.push_str(&next[next_first.len_utf8()..]);
        result
    } else if replaced_first.is_uppercase() && next_first.is_lowercase() {
        let mut result = String::new();
        result.extend(next_first.to_uppercase());
        result.push_str(&next[next_first.len_utf8()..]);
        result
    } else {
        next.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn raw_window_threshold_is_independent_of_final_decoder_silence() {
        let limit = 59 * SAMPLE_RATE + SAMPLE_RATE / 2;
        for length in [limit - SAMPLE_RATE / 2, limit - 1, limit, limit + 1] {
            let samples = vec![0.25; length];
            let plan = windows(&samples, limit);
            assert_eq!(plan.len(), if length <= limit { 1 } else { 2 });
            assert_eq!(plan.first().unwrap().start, 0);
            assert_eq!(plan.last().unwrap().end, length);
            for span in plan { assert!(span.len() <= limit); }
        }
    }

    #[test]
    fn background_windows_equal_final_windows_for_every_stop_and_capture_schedule() {
        let limit = 12 * SAMPLE_RATE;
        let audio: Vec<f32> = (0..75 * SAMPLE_RATE)
            .map(|i| {
                if i % (7 * SAMPLE_RATE) < SAMPLE_RATE {
                    0.0
                } else {
                    0.25
                }
            })
            .collect();
        for seconds in [1, 5, 12, 13, 25, 40, 75] {
            let final_audio = &audio[..seconds * SAMPLE_RATE];
            let final_plan = windows(final_audio, limit);
            for step in [SAMPLE_RATE / 10, SAMPLE_RATE, 5 * SAMPLE_RATE] {
                let mut start = 0;
                let mut prepared = Vec::new();
                for captured in (step..final_audio.len()).step_by(step) {
                    while let Some(window) = complete_window(&final_audio[..captured], start, limit)
                    {
                        start = window.end - overlap_samples(limit);
                        prepared.push(window);
                    }
                }
                assert_eq!(prepared, final_plan[..prepared.len()]);
                assert!(
                    prepared.len() < final_plan.len(),
                    "final context always waits for Stop"
                );
            }
        }
    }

    #[test]
    fn silence_alone_cannot_prepare_a_sentence_or_discard_samples() {
        let mut audio = vec![0.25; 5 * SAMPLE_RATE];
        audio.extend(vec![0.0; 5 * SAMPLE_RATE]);
        audio.extend(vec![0.25; 5 * SAMPLE_RATE]);
        assert_eq!(complete_window(&audio, 0, DEFAULT_MAX_WINDOW_SAMPLES), None);
        assert_eq!(
            windows(&audio, DEFAULT_MAX_WINDOW_SAMPLES),
            vec![0..audio.len()]
        );
    }

    #[test]
    fn ordinary_dictation_stays_one_complete_window() {
        let audio = vec![0.0; DEFAULT_MAX_WINDOW_SAMPLES];
        assert_eq!(
            windows(&audio, DEFAULT_MAX_WINDOW_SAMPLES),
            vec![0..audio.len()]
        );
    }

    #[test]
    fn long_audio_is_bounded_overlapping_and_losslessly_covered() {
        let audio = vec![0.25; 137 * SAMPLE_RATE];
        let planned = windows(&audio, DEFAULT_MAX_WINDOW_SAMPLES);
        assert!(planned.len() >= 3);
        assert_eq!(planned.first().unwrap().start, 0);
        assert_eq!(planned.last().unwrap().end, audio.len());
        for pair in planned.windows(2) {
            assert!(pair[0].len() <= DEFAULT_MAX_WINDOW_SAMPLES);
            assert_eq!(pair[0].end - pair[1].start, OVERLAP_SAMPLES);
            assert!(pair[1].start < pair[0].end);
        }
    }

    #[test]
    fn a_small_native_limit_is_never_inflated() {
        let limit = SAMPLE_RATE / 4;
        let audio = vec![0.25; SAMPLE_RATE];
        let planned = windows(&audio, limit);
        assert!(planned.len() > 1);
        assert_eq!(planned.first().unwrap().start, 0);
        assert_eq!(planned.last().unwrap().end, audio.len());
        assert!(planned.iter().all(|window| window.len() <= limit));
        assert!(planned
            .windows(2)
            .all(|pair| pair[1].start < pair[0].end && pair[1].start > pair[0].start));
    }

    #[test]
    fn later_context_replaces_an_artificial_boundary_with_one_wrong_word() {
        assert_eq!(
            stitch(
                "We can make the transcription much smoother.",
                "The transcript much smoother when the thought continues."
            ),
            "We can make the transcript much smoother when the thought continues."
        );
    }

    #[test]
    fn stitching_preserves_real_punctuation_inside_the_later_context() {
        assert_eq!(
            stitch(
                "First point is complete. Now the second point",
                "Now the second point continues here."
            ),
            "First point is complete. Now the second point continues here."
        );
    }

    #[test]
    fn stitching_restores_sentence_initial_capitalization() {
        assert_eq!(
            stitch(
                "First point is complete. Now the second point",
                "now the second point continues here."
            ),
            "First point is complete. Now the second point continues here."
        );
    }

    #[test]
    fn unmatched_results_are_never_discarded() {
        assert_eq!(
            stitch("alpha beta", "gamma delta"),
            "alpha beta gamma delta"
        );
    }

    #[test]
    fn spurious_later_prefix_is_dropped_before_the_shared_context() {
        assert_eq!(
            stitch(
                "We keep several shared boundary words together.",
                "Noise several shared boundary words together with context."
            ),
            "We keep several shared boundary words together with context."
        );
    }

    #[test]
    fn one_coincidental_word_never_collapses_distinct_speech() {
        assert_eq!(
            stitch(
                "This point is important.",
                "Important details start a new thought."
            ),
            "This point is important. Important details start a new thought."
        );
    }

    #[test]
    fn a_word_crossing_either_side_of_the_audio_seam_is_not_duplicated() {
        assert_eq!(
            stitch(
                "This sentence crosses the boundary word",
                "crosses the boundary word and continues safely."
            ),
            "This sentence crosses the boundary word and continues safely."
        );
        assert_eq!(
            stitch(
                "This sentence crosses the boundary",
                "sentence crosses the boundary word and continues safely."
            ),
            "This sentence crosses the boundary word and continues safely."
        );
    }
}
