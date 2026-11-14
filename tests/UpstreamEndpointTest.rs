#[path = "../src/endpoint.rs"]
mod endpoint;
#[path = "../src/inference_gate.rs"]
mod inference_gate;
#[test]
fn startup_zeros_and_steady_noise_are_not_speech() {
    let mut detector = endpoint::Endpointer::new();
    assert!(!detector.push(&vec![0.; 16000]));
    assert!(!detector.push(&vec![0.002; 32000]));
    assert!(!detector.speech_started());
}
#[test]
fn noisy_speech_detection_is_independent_of_callback_boundaries() {
    let mut audio = vec![0.002; 32000];
    audio.extend(vec![0.08; 16000]);
    audio.extend(vec![0.002; 32000]);
    let mut whole = endpoint::Endpointer::new();
    whole.push(&audio);
    let mut chunks = endpoint::Endpointer::new();
    for chunk in audio.chunks(173) {
        chunks.push(chunk);
    }
    assert!(whole.speech_started());
    assert_eq!(whole.speech_started(), chunks.speech_started());
    assert_eq!(whole.silence_ms(), chunks.silence_ms());
}
#[test]
fn silence_trimming_retains_internal_pauses_and_margins() {
    let mut audio = vec![0.; 160000];
    audio.extend(vec![0.1; 16000]);
    audio.extend(vec![0.; 48000]);
    audio.extend(vec![0.1; 16000]);
    audio.extend(vec![0.; 160000]);
    let range = endpoint::speech_range(&audio);
    assert!(range.start <= 160000 - 16000);
    assert!(range.end >= 240000 + 16000);
    assert!(range.len() < audio.len());
}
#[test]
fn ambiguous_quiet_speech_is_preserved() {
    let audio = vec![0.001; 32000];
    assert_eq!(endpoint::speech_range(&audio), 0..audio.len());
    assert_eq!(endpoint::speech_range(&[0.; 32000]), 0..0);
}

#[test]
fn continuous_speech_does_not_become_its_own_noise_floor() {
    let mut detector = endpoint::Endpointer::new();
    detector.push(&vec![0.002; 32000]);
    detector.push(&vec![0.08; 160000]);
    assert!(detector.speech_started());
    assert!(detector.is_voiced());
    assert_eq!(detector.silence_ms(), 0);
}
#[test]
fn immediate_normal_speech_is_detected_without_startup_quiet() {
    let mut detector = endpoint::Endpointer::new();
    detector.push(&vec![0.08; 16000]);
    assert!(detector.speech_started());
    assert!(detector.is_voiced());
}
