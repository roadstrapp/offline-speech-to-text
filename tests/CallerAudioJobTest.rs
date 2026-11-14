// Host test for caller-audio window scheduling (src/caller_audio_job.rs and
// the long-audio helpers it builds on); the module's own #[test]s run here.
#[allow(dead_code)]
#[path = "../src/audio.rs"]
mod audio;
#[allow(dead_code)]
#[path = "../src/long_audio.rs"]
mod long_audio;
#[allow(dead_code)]
#[path = "../src/file_audio.rs"]
mod file_audio;
#[allow(dead_code)]
#[path = "../src/caller_audio_job.rs"]
mod caller_audio_job;
