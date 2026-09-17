#[path = "../src/audio.rs"]
mod audio;
#[path = "../src/long_audio_chunking.rs"]
mod long_audio_chunking;

#[test]
fn short_audio_stays_in_one_run() {
    let samples = vec![0.25; 11 * 16_000];
    let plan = long_audio_chunking::Plan::for_conformer();
    assert_eq!(samples.len(), plan.next_chunk_len(&samples));
}

#[test]
fn conformer_audio_uses_small_bounded_runs() {
    let samples = vec![0.25; 60 * 16_000];
    let take = long_audio_chunking::Plan::for_conformer().next_chunk_len(&samples);
    assert!(take >= 12 * 16_000);
    assert!(take <= 15 * 16_000);
}

#[test]
fn whisper_stays_aligned_to_its_thirty_second_window() {
    let samples = vec![0.25; 60 * 16_000];
    let take = long_audio_chunking::Plan::for_whisper().next_chunk_len(&samples);
    assert!(take >= 20 * 16_000);
    assert!(take <= 30 * 16_000);
}

#[test]
fn long_audio_prefers_a_quiet_boundary() {
    let mut samples = vec![0.25; 30 * 16_000];
    for sample in &mut samples[13 * 16_000..13 * 16_000 + 1_600] {
        *sample = 0.0;
    }
    let take = long_audio_chunking::Plan::for_conformer().next_chunk_len(&samples);
    assert!((take as isize - (13 * 16_000 + 800) as isize).abs() <= 800);
}

#[test]
fn every_long_input_makes_progress() {
    for plan in [
        long_audio_chunking::Plan::for_conformer(),
        long_audio_chunking::Plan::for_whisper(),
    ] {
        for seconds in [1, 11, 12, 15, 16, 30, 31, 60, 599, 600] {
            let samples = vec![f32::NAN; seconds * 16_000];
            let take = plan.next_chunk_len(&samples);
            assert!(take > 0);
            assert!(take <= samples.len());
            if samples.len() > plan.max_run_samples() {
                assert!(take < samples.len());
            } else {
                assert_eq!(take, samples.len());
            }
        }
    }
}

#[test]
fn empty_and_silent_inputs_are_bounded() {
    let plan = long_audio_chunking::Plan::for_conformer();
    assert_eq!(0, plan.next_chunk_len(&[]));
    let silence = vec![0.0; 20 * 16_000];
    let take = plan.next_chunk_len(&silence);
    assert!(take > 0 && take < silence.len());
}
