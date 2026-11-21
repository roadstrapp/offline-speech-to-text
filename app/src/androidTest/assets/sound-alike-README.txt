Synthetic test audio for the sound-alike device probes (sound_alike_probe,
preferred_terms_timing). The sound-alike-*.wav clips are SYNTHETIC speech generated
with eSpeak NG 1.52.0 (https://github.com/espeak-ng/espeak-ng, GPL-3.0-or-later),
then resampled to 16 kHz mono PCM16 with ffmpeg. They contain no recorded human speech.

  sound-alike-tail-scale.wav    "I set up tail scale on the server today"        (en-us)
  sound-alike-noster.wav        "I read about the noster protocol this morning"  (en-us)
  sound-alike-mixed-es-en.wav   "Yo creo que nuestro proyecto" (es) followed by
                                "is going to work well for the team" (en-us)
  sound-alike-control-fish.wav  "The tail scale of the fish was bright"          (en-us)

sound-alike-terms-128.txt lists 128 preferred terms (20 realistic names and product
terms first, the rest public-corpus proper nouns) used for the timing probe.
