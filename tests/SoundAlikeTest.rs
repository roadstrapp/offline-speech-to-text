#[allow(dead_code)]
#[path = "../src/audio.rs"]
mod audio;
#[allow(dead_code)]
#[path = "../src/english_fillers.rs"]
mod english_fillers;
#[allow(dead_code)]
#[path = "../src/long_audio.rs"]
mod long_audio;
#[allow(dead_code)]
#[path = "../src/personal_corrections.rs"]
mod personal_corrections;
#[allow(dead_code)]
#[path = "../src/preferred_terms.rs"]
mod preferred_terms;
#[allow(dead_code)]
#[path = "baseline/preferred_terms_9070b96.rs"]
mod preferred_terms_9070b96;
#[allow(dead_code)]
#[path = "../src/transcript_cleanup.rs"]
mod transcript_cleanup;
#[allow(dead_code)]
#[path = "../src/transcript_guard.rs"]
mod transcript_guard;

use preferred_terms::sound_alike::{self, Gate};
use std::ops::Range;
use transcript_cleanup::OutputLanguage;

const ENGLISH: Gate = Gate { single: true, multi: true, multi_common_only: false };
const CLOSED: Gate = Gate { single: false, multi: false, multi_common_only: false };
const WORD_LIST: &str = include_str!("../src/sound_alike_words_en.txt");
const AMERICAN_SHA256: &str = "9ee3f26881da2cc824c856dbfff3a7c69da492c8b26761e8445361727fe67ad8";
const BRITISH_SHA256: &str = "8d084d7ba508701e91bec30b95640fbba25181eb0ffc093568700c59d9454163";
const NAMES_SHA256: &str = "87910fe9b00a5bfad567450a9add51ca5d3bfbc3e1175ebc6ece3cbfef7a735f";

fn terms(written: &[&str]) -> Vec<String> {
    written.iter().map(|term| term.to_string()).collect()
}

fn old(text: &str, written: &[&str]) -> String {
    preferred_terms::apply_with_ranges(text, &terms(written), &[]).0
}

fn sound(text: &str, written: &[&str]) -> String {
    preferred_terms::apply_with_sound_alike(text, &terms(written), &[], ENGLISH, &[]).0
}

fn finish(text: &str, language: OutputLanguage, written: &[&str]) -> String {
    transcript_cleanup::finish(text.to_string(), language, Some(&terms(written)), None)
}

fn fin(text: &str, written: &[&str]) -> String {
    finish(text, OutputLanguage::English, written)
}

fn baseline(text: &str, written: &[String], protected: &[Range<usize>]) -> (String, Vec<Range<usize>>) {
    preferred_terms_9070b96::apply_with_ranges(text, written, protected)
}

fn key_of(word: &str) -> String {
    sound_alike::key(&sound_alike::fold(word).unwrap())
}

#[test]
fn keys_follow_the_documented_table() {
    let table: &[(&str, &[&str])] = &[
        ("NVSTR", &["Nostr"]),
        ("NVSTVR", &["Noster", "nostir"]),
        ("KVTLVN", &["Kotlin", "Cotlin", "cattlein"]),
        ("SVPSTVR", &["Zapstore"]),
        ("TVLSKVL", &["Tailscale"]),
        ("TVLVSKVL", &["talescale"]),
        ("PVRVKVT", &["Parakeet"]),
        ("PVRKVT", &["parquet"]),
        ("BVTKVN", &["Bitcoin"]),
        ("MVRMVT", &["Marmot"]),
        ("VMV0VST", &["Amethyst"]),
        ("JVLVN", &["Jaelyn", "Jaylinn", "Jaylen", "Jailin", "jaylin"]),
        ("SKVL", &["scale", "Scalle"]),
        ("SKVLVMVDVL", &["scalemodel"]),
        ("MVSTVRD", &["mustard"]),
        ("MVSTVR", &["master"]),
        ("MVNSTVR", &["monster"]),
    ];
    for (expected, words) in table {
        for word in *words {
            assert_eq!(key_of(word), *expected, "{word}");
        }
    }
    assert_eq!(key_of("knight"), key_of("night"));
    assert_eq!(key_of("phone"), "FVN");
    assert_eq!(key_of("yes"), "YVS");
    assert_eq!(key_of("church"), "XVRX");
    assert_eq!(key_of("budget"), "BVJVT");
}

#[test]
fn keys_match_only_by_one_inner_vowel() {
    assert!(sound_alike::keys_match(b"NVSTR", b"NVSTVR"));
    assert!(sound_alike::keys_match(b"NVSTVR", b"NVSTR"));
    assert!(sound_alike::keys_match(b"TVLSKVL", b"TVLVSKVL"));
    assert!(sound_alike::keys_match(b"PVRVKVT", b"PVRKVT"));
    assert!(!sound_alike::keys_match(b"MVSTVRD", b"MVSTVR"));
    assert!(!sound_alike::keys_match(b"MVSTVR", b"MVNSTVR"));
    assert!(!sound_alike::keys_match(b"MVSTVRD", b"MVNSTVR"));
    assert!(!sound_alike::keys_match(b"VNSTR", b"NSTR"));
    assert!(!sound_alike::keys_match(b"NVSTVVR", b"NVSTR"));
    assert_eq!(sound_alike::osa(b"tailscale", b"talescale"), 2);
    assert_eq!(sound_alike::osa(b"ab", b"ba"), 1);
    assert_eq!(
        [6, 7, 10, 11, 40].map(sound_alike::budget),
        [1, 2, 2, 3, 3]
    );
}

#[test]
fn owner_examples() {
    for (text, term, expected) in [
        ("Noster", "Nostr", "Nostr"),
        ("jaylin", "Jaylinn", "Jaylinn"),
        ("amethist", "Amethyst", "Amethyst"),
    ] {
        assert_eq!(old(text, &[term]), expected);
        assert_eq!(sound(text, &[term]), expected);
        assert_eq!(fin(text, &[term]), expected);
    }
    for (text, term) in [
        ("no stir", "Nostr"),
        ("zap store", "Zapstore"),
        ("tail scale", "Tailscale"),
        ("bit coin", "Bitcoin"),
        ("para keet", "Parakeet"),
        ("Cotlin", "Kotlin"),
        ("cattlein", "Kotlin"),
    ] {
        assert_eq!(old(text, &[term]), text, "{text}");
        assert_eq!(sound(text, &[term]), term, "{text}");
        assert_eq!(fin(text, &[term]), term, "{text}");
    }
    assert_eq!(
        fin("we moved the node to tail scale, and then zap store.", &["Tailscale", "Zapstore"]),
        "we moved the node to Tailscale, and then Zapstore."
    );
}

#[test]
fn ties_change_nothing() {
    assert_eq!(sound("Jailin", &["Jaelyn", "Jaylinn"]), "Jailin");
    assert_eq!(old("Jaylin", &["Jaylen", "Jaelyn"]), "Jaylen");
    assert_eq!(sound("Jaylin", &["Jaylen", "Jaelyn"]), "Jaylen");
    assert_eq!(old("Noster", &["Nostr", "Nostar"]), "Noster");
    assert_eq!(sound("Noster", &["Nostr", "Nostar"]), "Noster");
}

#[test]
fn spelled_matches_win_over_sound_alikes() {
    let listed = ["Jaylinn", "Jaelyn", "Caitlin", "Ximena"];
    assert_eq!(old("send it to Jaylin please", &listed), "send it to Jaylinn please");
    assert_eq!(sound("send it to Jaylin please", &listed), "send it to Jaylinn please");
    assert_eq!(fin("send it to Jaylin please", &listed), "send it to Jaylinn please");
    assert_eq!(sound("Jailin", &["Jaelyn"]), "Jaelyn");
}

/// Many spelled and sound-alike proposals side by side: the overlap check
/// must stay near linear. A pairwise check takes about 30 times longer here.
#[test]
fn many_spelled_and_heard_proposals() {
    let count = 50_000;
    let text = "nostrr tail scale ".repeat(count);
    let started = std::time::Instant::now();
    let (out, ranges) =
        preferred_terms::apply_with_sound_alike(&text, &terms(&["Nostr", "Tailscale"]), &[], ENGLISH, &[]);
    assert!(started.elapsed() < std::time::Duration::from_secs(20), "{:?}", started.elapsed());
    assert_eq!(out, "Nostr Tailscale ".repeat(count));
    assert_eq!(ranges.len(), 2 * count);
}

#[test]
fn names_and_function_words_stay() {
    for (text, term) in [
        ("Julien", "Jaelyn"),
        ("Zaman", "Ximena"),
        ("Simona", "Ximena"),
        ("Simeon", "Ximena"),
        ("the cattle in the barn", "Caitlin"),
        ("so by an", "Siobhan"),
        ("then die", "Thandiwe"),
        ("than de", "Thandiwe"),
        ("then do we", "Thandiwe"),
    ] {
        assert_eq!(sound(text, &[term]), text, "{text}");
    }
    assert_eq!(sound("I posted it on no stir yesterday", &["Nostr"]), "I posted it on Nostr yesterday");
    assert_eq!(sound("I posted it on Noster yesterday", &["Nostr"]), "I posted it on Nostr yesterday");
    assert_eq!(sound("I paid in bit coin", &["Bitcoin"]), "I paid in Bitcoin");
    assert_eq!(sound("download it from zap store", &["Zapstore"]), "download it from Zapstore");
    assert_eq!(sound("connect through tale scale", &["Tailscale"]), "connect through Tailscale");
    assert_eq!(sound("the para keet model is fast", &["Parakeet"]), "the Parakeet model is fast");
    assert!(sound_alike::FUNCTION_WORDS.windows(2).all(|pair| pair[0] < pair[1]));
    assert!(sound_alike::is_function_word("in") && !sound_alike::is_function_word("no"));
}

#[test]
fn overlapping_proposals_resolve_whole_or_not_at_all() {
    assert_eq!(old("tail scale", &["Scalle", "Tailscale"]), "tail Scalle");
    assert_eq!(sound("tail scale", &["Scalle", "Tailscale"]), "tail Scalle");
    assert_eq!(sound("tail scale model", &["Tailscale", "Scalemodel"]), "tail scale model");
    assert_eq!(
        sound("alpha beta gamma delta", &["Alpha Beta", "Beta Gamma Delta"]),
        "Alpha Beta gamma delta"
    );
    assert_eq!(sound("enpub", &["npub", "Enpaub"]), "enpub");
    assert_eq!(sound("Enpub", &["Enpub", "npub"]), "Enpub");
    assert_eq!(sound("tail scale", &["Tailscale", "Scale"]), "tail Scale");
}

#[test]
fn eligibility_and_window_limits() {
    assert!(sound_alike::Terms::new(&["Nost"]).is_empty());
    assert!(sound_alike::Terms::new(&["NOSTR"]).is_empty());
    assert!(sound_alike::Terms::new(&["Aeiou"]).is_empty());
    assert!(!sound_alike::Terms::new(&["Nostr"]).is_empty());
    assert_eq!(sound("ta il sca le", &["Tailscale"]), "ta il sca le");
    assert_eq!(sound("ta il scale", &["Tailscale"]), "Tailscale");
    assert_eq!(sound("TAIL SCALE", &["Tailscale"]), "TAIL SCALE");
    assert_eq!(sound("Tail Scale", &["Tailscale"]), "Tailscale");
    assert_eq!(sound("CotLin", &["Kotlin"]), "CotLin");
    assert_eq!(sound("no stir", &["NOSTR"]), "no stir");
    assert_eq!(sound("Nestor", &["Nostr"]), "Nestor");
    assert_eq!(sound("parquet", &["Parakeet"]), "parquet");
    assert_eq!(sound("bit uh coin", &["Bitcoin"]), "bit uh coin");
    assert_eq!(fin("bit uh coin", &["Bitcoin"]), "bit coin");
    assert_eq!(sound("bitcoins", &["Bitcoin"]), "bitcoins");
    assert_eq!(sound("tail, scale", &["Tailscale"]), "tail, scale");
    assert_eq!(sound("tail  scale", &["Tailscale"]), "tail  scale");
    assert_eq!(sound("tail's scale", &["Tailscale"]), "tail's scale");
    assert_eq!(sound("no stir's", &["Nostr"]), "Nostr's");
}

#[test]
fn unicode_tokens_compared_with_the_baseline() {
    let nostr = terms(&["Nostr"]);
    let both = |text: &str| {
        (
            baseline(text, &nostr, &[]).0,
            preferred_terms::apply_with_ranges(text, &nostr, &[]).0,
            preferred_terms::apply_with_sound_alike(text, &nostr, &[], ENGLISH, &[]).0,
        )
    };
    // Letters joined to a combining mark or a hyphen-like mark are no
    // longer guessed.
    for (text, suffix) in [
        ("noster\u{0301}", "\u{0301}"),
        ("noster\u{2010}x", "\u{2010}x"),
        ("noster\u{2011}x", "\u{2011}x"),
        ("noster\u{00AD}x", "\u{00AD}x"),
    ] {
        let (before, after, with_sound) = both(text);
        assert_eq!(before, format!("Nostr{suffix}"));
        assert_eq!(after, text);
        assert_eq!(with_sound, text);
    }
    // A symbol joined to the word makes the whole token ineligible too (plan
    // E), with or without sound-alike matching.
    let (before, after, with_sound) = both("nostrr\u{1F642}");
    assert_eq!(before, "Nostr\u{1F642}");
    assert_eq!(after, "nostrr\u{1F642}");
    assert_eq!(with_sound, "nostrr\u{1F642}");
    for text in ["noster-x", "noster2", "noster_x", "noster\u{05B0}", "no στιρ", "nosté"] {
        let (before, after, with_sound) = both(text);
        assert_eq!(before, text);
        assert_eq!(after, text);
        assert_eq!(with_sound, text);
    }
    let (before, after, with_sound) = both("noster\u{02BC}s");
    assert_eq!(before, "noster\u{02BC}s");
    assert_eq!(after, "noster\u{02BC}s");
    assert_eq!(with_sound, "Nostr\u{02BC}s");
    for (text, expected) in [
        ("noster's", "Nostr's"),
        ("noster\u{2018}s", "Nostr\u{2018}s"),
        ("nostér", "Nostr"),
    ] {
        let (before, after, with_sound) = both(text);
        assert_eq!(before, expected);
        assert_eq!(after, expected);
        assert_eq!(with_sound, expected);
    }
}

#[test]
fn protected_ranges_move_with_edits() {
    let nostr = terms(&["Nostr"]);
    let (text, ranges) =
        preferred_terms::apply_with_sound_alike("ab no stir cd", &nostr, &[0..3, 10..13], ENGLISH, &[]);
    assert_eq!(text, "ab Nostr cd");
    assert_eq!(ranges, vec![0..3, 3..8, 8..11]);
    let (text, ranges) =
        preferred_terms::apply_with_sound_alike("héllo no stir ünï", &nostr, &[], ENGLISH, &[]);
    assert_eq!(text, "héllo Nostr ünï");
    assert_eq!(ranges.iter().map(|range| &text[range.clone()]).collect::<Vec<_>>(), ["Nostr"]);
    // A protected word is never a window.
    let (text, _) = preferred_terms::apply_with_sound_alike("no stir", &nostr, &[3..7], ENGLISH, &[]);
    assert_eq!(text, "no stir");
}

#[test]
fn growth_is_bounded_over_the_whole_output() {
    let long = format!("Ko{}lin", "t".repeat(4000));
    let written = vec![long.clone()];
    let one = "we use cotlin";
    let (text, _) = preferred_terms::apply_with_sound_alike(one, &written, &[], ENGLISH, &[]);
    assert_eq!(text, format!("we use {long}"));
    let two = "cotlin and cotlin ok";
    let (text, ranges) =
        preferred_terms::apply_with_sound_alike(two, &written, &[18..20], ENGLISH, &[]);
    assert_eq!(text, two);
    assert_eq!(ranges, vec![18..20]);
    // The bound covers the trailing text after the last edit (plan F); the
    // baseline only checked the output up to its last word.
    let kelvin = terms(&["\u{212A}a"]);
    let tail = format!("{}!!!", "ka ".repeat(2049));
    assert_eq!(baseline(&tail, &kelvin, &[]).0, format!("{}!!!", "\u{212A}a ".repeat(2049)));
    assert_eq!(preferred_terms::apply_with_ranges(&tail, &kelvin, &[]).0, tail);
    assert_eq!(preferred_terms::apply_with_sound_alike(&tail, &kelvin, &[], ENGLISH, &[]).0, tail);
}

/// Stitch windows as the decoder does, tracking unaligned joins.
fn stitch_windows(windows: &[&str]) -> (String, Vec<usize>) {
    let mut assembled = String::new();
    let mut joins = Vec::new();
    for window in windows {
        let (stitched, outcome) = long_audio::stitch_with_join(&assembled, window.trim());
        match outcome {
            long_audio::StitchOutcome::Aligned { truncated_at } => {
                joins.retain(|&join| join < truncated_at)
            }
            long_audio::StitchOutcome::Fallback { join } => joins.push(join),
        }
        assembled = stitched;
    }
    (assembled, joins)
}

#[test]
fn windows_never_span_an_unaligned_join() {
    let tailscale = terms(&["Tailscale"]);
    let (text, joins) = stitch_windows(&["we use tail", "scale today"]);
    assert_eq!(text, "we use tail scale today");
    assert_eq!(joins, vec![12]);
    let finished = |text: &str, joins: &[usize]| {
        transcript_cleanup::finish_with_joins(
            text.to_string(),
            OutputLanguage::English,
            Some(&tailscale),
            None,
            joins,
        )
    };
    assert_eq!(finished(&text, &joins), "we use tail scale today");
    assert_eq!(finished(&text, &[]), "we use Tailscale today");
    // A join at a window edge does not split it; one inside does.
    assert_eq!(finished("we use tail scale today", &[7, 18]), "we use Tailscale today");
    assert_eq!(finished("we use tail scale today", &[2, 7, 17, 20]), "we use Tailscale today");
    assert_eq!(finished("we use tail scale today", &[2, 11, 20]), "we use tail scale today");
    assert_eq!(finished("we use tail scale today", &[20, 2, 12]), "we use tail scale today");

    let (text, joins) = stitch_windows(&["we use tail", "scale today", "use tail scale today and more"]);
    assert_eq!(text, "we use tail scale today and more");
    assert!(joins.is_empty());
    assert_eq!(finished(&text, &joins), "we use Tailscale today and more");

    let (text, joins) =
        stitch_windows(&["we deploy the tail scale node", "the tail scale node now"]);
    assert!(joins.is_empty());
    assert_eq!(finished(&text, &joins), "we deploy the Tailscale node now");

    let zero_width = "we use tail\u{200B}scale today";
    assert_eq!(finished(zero_width, &[]), zero_width);

    // Corrections shift the join with the text they rewrite.
    let rules = personal_corrections::parse("we use => I use\n").unwrap();
    let (text, joins) = stitch_windows(&["we use tail", "scale today"]);
    let out = transcript_cleanup::finish_with_joins(
        text,
        OutputLanguage::English,
        Some(&tailscale),
        Some(&rules),
        &joins,
    );
    assert_eq!(out, "I use tail scale today");
}

#[test]
fn gate_follows_language_evidence() {
    let nostr = ["Nostr"];
    assert_eq!(finish("no stir", OutputLanguage::Other, &nostr), "no stir");
    assert_eq!(finish("cotlin", OutputLanguage::Unknown, &["Kotlin"]), "cotlin");
    assert_eq!(finish("no stir", OutputLanguage::Unknown, &nostr), "Nostr");
    assert_eq!(finish("tail scale", OutputLanguage::Unknown, &["Tailscale"]), "Tailscale");
    assert_eq!(finish("tail scale.", OutputLanguage::Unknown, &["Tailscale"]), "Tailscale.");
    assert_eq!(finish("tail scale\u{2026}", OutputLanguage::Unknown, &["Tailscale"]), "tail scale\u{2026}");
    assert_eq!(finish("tail scale\u{2026}", OutputLanguage::English, &["Tailscale"]), "Tailscale\u{2026}");
    assert_eq!(finish("el nuestro amigo", OutputLanguage::Unknown, &nostr), "el nuestro amigo");
    assert_eq!(
        finish("tail scale está", OutputLanguage::Unknown, &["Tailscale"]),
        "tail scale está"
    );
    assert_eq!(
        finish("I think that the cotlin build has failed", OutputLanguage::Unknown, &["Kotlin"]),
        "I think that the Kotlin build has failed"
    );
}

#[test]
fn snapshots_show_each_stage() {
    let raw = "uh the tail scale and go now ok fine";
    let (text, snapshots) = transcript_cleanup::finish_snapshots(
        raw.to_string(),
        OutputLanguage::Unknown,
        Some(&terms(&["Tailscale"])),
        None,
        &[],
    );
    assert_eq!(snapshots, vec![raw.to_string(), "uh the Tailscale and go now ok fine".to_string()]);
    assert_eq!(text, "the Tailscale and go now ok fine");
}

#[test]
fn word_list_is_sorted_folded_and_complete() {
    let (header, body) = WORD_LIST.split_once('\n').unwrap();
    assert!(header.starts_with("# scowl 2020.12.07-4"));
    for digest in [AMERICAN_SHA256, BRITISH_SHA256, NAMES_SHA256] {
        assert!(header.contains(digest), "{digest}");
    }
    let words: Vec<&str> = body.lines().collect();
    assert!(words.len() > 150_000, "{}", words.len());
    assert!(words.windows(2).all(|pair| pair[0] < pair[1]));
    for word in &words {
        assert!(word.len() >= 2 && word.bytes().all(|b| b.is_ascii_lowercase()), "{word}");
        assert!(sound_alike::is_common_word(word), "{word}");
    }
    for word in ["nestor", "parquet", "scale", "tail", "stir", "no", "simeon", "julien", "simona", "zaman", "subhan"] {
        assert!(sound_alike::is_common_word(word), "{word}");
    }
    for word in ["nostr", "noster", "naster", "tailscale", "zapstore", "cotlin", "a", "", "zzzzzz"] {
        assert!(!sound_alike::is_common_word(word), "{word}");
    }
}

/// Deterministic pseudo-random choice.
struct Lcg(u64);

impl Lcg {
    fn next(&mut self, bound: usize) -> usize {
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        ((self.0 >> 33) as usize) % bound
    }
}

const FRAGMENTS: &[&str] = &[
    "noster", "Noster's", "nostr", "NOSTR", "no stir", "jaylin", "Jaylen", "amethist",
    "en pub", "enpop", "npub", "n pop", "tail scale", "Tail Scale", "zap store", "cotlin",
    "the", "and", "uh", "um", "ok", ",", ".", "?", "!", "\n", "  ", "-", "'", "\u{2019}",
    "nostér", "naïve", "café", "στιρ", "東京", "🙂", "2fa", "x86", "a", "I", "we",
    "parakeat", "paraket", "Bitcoin", "bit coin", "marmot", "Marmot's", "gpt-4", "co-op",
    "(noster)", "\"jaylin\"", "noster.", "UM", "Um", "intent", "brand",
];

/// Fragments the fuzzy tokenizer deliberately treats differently when they
/// touch a word (plan section E); the differential corpus keeps them apart.
const ISOLATED: &[&str] = &[
    "🙂", "東京", "στιρ", "2fa", "x86", "gpt-4", "co-op", "-", "'", "\u{2019}",
];

const TERM_SETS: &[&[&str]] = &[
    &["Nostr"],
    &["Nostr", "npub"],
    &["Jaylen", "Jaelyn"],
    &["Amethyst", "Kotlin", "Bitcoin"],
    &["Parakeet", "UM"],
    &["Tail Scale", "Zap Store"],
    &["Marmot", "GPT-4", "2FA"],
    &["Nostr", "Nostr", "nostr"],
    &["npub", "Enpaub", "Scalle", "Tailscale"],
];

fn corpus() -> Vec<String> {
    let mut random = Lcg(7);
    (0..3000)
        .map(|_| {
            let count = 1 + random.next(9);
            let mut text = String::new();
            let mut previous = "";
            for _ in 0..count {
                let fragment = FRAGMENTS[random.next(FRAGMENTS.len())];
                let glued = random.next(3) == 0 && !ISOLATED.contains(&previous) && !ISOLATED.contains(&fragment);
                if !text.is_empty() && !glued {
                    text.push(' ');
                }
                text.push_str(fragment);
                previous = fragment;
            }
            text
        })
        .collect()
}

#[test]
fn closed_gate_matches_the_baseline() {
    let rules = personal_corrections::parse("intent => Um\nbrand => iOS\nthe and => thee\n").unwrap();
    let mut checked = 0;
    for text in corpus() {
        let (corrected, protected) = personal_corrections::apply_with_ranges(&text, &rules);
        for set in TERM_SETS {
            let written = terms(set);
            let expected = baseline(&corrected, &written, &protected);
            assert_eq!(
                preferred_terms::apply_with_ranges(&corrected, &written, &protected),
                expected,
                "{corrected:?} {set:?}"
            );
            assert_eq!(
                preferred_terms::apply_with_sound_alike(&corrected, &written, &protected, CLOSED, &[]),
                expected,
                "{corrected:?} {set:?}"
            );
            checked += 1;
        }
    }
    assert_eq!(checked, 3000 * TERM_SETS.len());
}

#[test]
fn other_language_finish_matches_the_baseline() {
    for text in corpus() {
        for set in TERM_SETS {
            let written = terms(set);
            let expected = baseline(&text, &written, &[]).0;
            assert_eq!(finish(&text, OutputLanguage::Other, set), expected, "{text:?} {set:?}");
            let _ = written;
        }
    }
}

fn scan(text: &str, written: &[&str], bucketed: bool) -> Vec<(Range<usize>, String)> {
    let tokens = sound_alike::tokens(text);
    let terms = sound_alike::Terms::new(written);
    let mut out = Vec::new();
    if bucketed {
        sound_alike::candidates(text, &tokens, &terms, ENGLISH, &[], |_, _| true, &mut out);
    } else {
        sound_alike::candidates_unbucketed(text, &tokens, &terms, ENGLISH, &[], |_, _| true, &mut out);
    }
    out.into_iter().map(|candidate| (candidate.range, candidate.written.to_string())).collect()
}

#[test]
fn length_buckets_lose_no_candidate() {
    let written = ["Tailscalenet", "Tailscale", "Zapstore", "Parakeet", "Nostr", "Bitcoin"];
    for (text, difference) in [("taail scaale net", 2), ("taail scaale neet", 3)] {
        let found = scan(text, &written, true);
        assert!(found.contains(&(0..text.len(), "Tailscalenet".to_string())), "{text}");
        let joined: String = text.split(' ').collect();
        assert_eq!(joined.len() - "tailscalenet".len(), difference);
        assert_eq!(found, scan(text, &written, false));
    }
    let mut random = Lcg(11);
    let pieces = [
        "tail", "scale", "taail", "scaale", "net", "neet", "zap", "store", "stor", "para",
        "keet", "kit", "no", "stir", "ster", "bit", "coin", "coins", "a", "the",
    ];
    for _ in 0..2000 {
        let text: Vec<&str> = (0..1 + random.next(6)).map(|_| pieces[random.next(pieces.len())]).collect();
        let text = text.join(" ");
        assert_eq!(scan(&text, &written, true), scan(&text, &written, false), "{text}");
    }
}

/// Regenerates `src/sound_alike_words_en.txt` from the SCOWL size-70 lists
/// and the Faker person names:
/// SOUND_ALIKE_AMERICAN=<american-english-large>
/// SOUND_ALIKE_BRITISH=<british-english-large>
/// SOUND_ALIKE_NAMES=<faker/providers/person> SOUND_ALIKE_WORDS_OUT=<file>.
#[test]
#[ignore]
fn generate_common_words() {
    let american = std::env::var("SOUND_ALIKE_AMERICAN").expect("SOUND_ALIKE_AMERICAN");
    let british = std::env::var("SOUND_ALIKE_BRITISH").expect("SOUND_ALIKE_BRITISH");
    let names = std::env::var("SOUND_ALIKE_NAMES").expect("SOUND_ALIKE_NAMES");
    let output = std::env::var("SOUND_ALIKE_WORDS_OUT").expect("SOUND_ALIKE_WORDS_OUT");
    assert_eq!(sha256(&[american.as_str()], None, None), AMERICAN_SHA256, "unexpected american list");
    assert_eq!(sha256(&[british.as_str()], None, None), BRITISH_SHA256, "unexpected british list");
    let mut locales: Vec<String> = std::fs::read_dir(&names)
        .unwrap()
        .map(|entry| entry.unwrap().file_name().into_string().unwrap())
        .filter(|locale| std::path::Path::new(&names).join(locale).join("__init__.py").is_file())
        .map(|locale| format!("{locale}/__init__.py"))
        .collect();
    locales.sort();
    let paths: Vec<&str> = locales.iter().map(String::as_str).collect();
    let listing = sha256_listing(&paths, &names);
    assert_eq!(sha256(&[], None, Some(&listing)), NAMES_SHA256, "unexpected name providers");
    let mut raw = String::new();
    for list in [&american, &british] {
        for line in std::fs::read_to_string(list).unwrap().lines() {
            if !line.contains(['\'', '\u{2019}']) {
                raw.push_str(line);
                raw.push('\n');
            }
        }
    }
    for path in &locales {
        let source = std::fs::read_to_string(std::path::Path::new(&names).join(path)).unwrap();
        for name in person_names(&source) {
            raw.push_str(&name);
            raw.push('\n');
        }
    }
    let mut words: Vec<String> =
        raw.lines().filter_map(sound_alike::fold).filter(|word| word.len() >= 2).collect();
    words.sort();
    words.dedup();
    let mut file = format!(
        "# scowl 2020.12.07-4 american-english-large sha256 {AMERICAN_SHA256}, british-english-large sha256 {BRITISH_SHA256}; \
         faker 33.3.1 person first/last names, providers sha256sum listing sha256 {NAMES_SHA256}; folded, 2+ letters\n"
    );
    for word in words {
        file.push_str(&word);
        file.push('\n');
    }
    std::fs::write(output, file).unwrap();
}

/// `sha256sum` of files (relative to `dir`) or of `stdin`.
fn sha256(paths: &[&str], dir: Option<&str>, stdin: Option<&str>) -> String {
    use std::io::Write;
    let mut command = std::process::Command::new("sha256sum");
    command.args(paths).stdout(std::process::Stdio::piped());
    if let Some(dir) = dir {
        command.current_dir(dir);
    }
    if stdin.is_some() {
        command.stdin(std::process::Stdio::piped());
    }
    let mut child = command.spawn().unwrap();
    if let Some(stdin) = stdin {
        child.stdin.take().unwrap().write_all(stdin.as_bytes()).unwrap();
    }
    let digest = child.wait_with_output().unwrap();
    assert!(digest.status.success());
    let digest = String::from_utf8(digest.stdout).unwrap();
    digest.split_whitespace().next().unwrap().to_string()
}

/// The full `sha256sum` output for `paths` relative to `dir`.
fn sha256_listing(paths: &[&str], dir: &str) -> String {
    let listing = std::process::Command::new("sha256sum").args(paths).current_dir(dir).output().unwrap();
    assert!(listing.status.success());
    String::from_utf8(listing.stdout).unwrap()
}

/// String literals assigned to `*first_name*` or `*last_name*` attributes in
/// a Faker person provider, skipping `*format*` attributes and templates.
fn person_names(source: &str) -> Vec<String> {
    let bytes = source.as_bytes();
    let mut names = Vec::new();
    let mut at = 0;
    while at < bytes.len() {
        let line_end = source[at..].find('\n').map_or(bytes.len(), |index| at + index);
        let line = source[at..line_end].trim_start();
        let ident_len = line.bytes().take_while(|b| b.is_ascii_alphanumeric() || *b == b'_').count();
        let ident = &line[..ident_len];
        let rest = line[ident_len..].trim_start();
        let assigned = rest.starts_with(':') || (rest.starts_with('=') && !rest.starts_with("=="));
        if !(assigned && (ident.contains("first_name") || ident.contains("last_name")) && !ident.contains("format")) {
            at = line_end + 1;
            continue;
        }
        let Some(equals) = source[at..line_end].find('=') else {
            at = line_end + 1;
            continue;
        };
        let mut index = at + equals + 1;
        let mut depth = 0usize;
        while index < bytes.len() {
            match bytes[index] {
                b'#' => index += source[index..].find('\n').unwrap_or(bytes.len() - index),
                b'(' | b'[' | b'{' => depth += 1,
                b')' | b']' | b'}' => depth = depth.saturating_sub(1),
                b'\n' if depth == 0 => break,
                quote @ (b'"' | b'\'') => {
                    let mut text = String::new();
                    index += 1;
                    while index < bytes.len() && bytes[index] != quote {
                        let ch = source[index..].chars().next().unwrap();
                        text.push(ch);
                        index += ch.len_utf8();
                    }
                    if !text.contains('{') {
                        names.push(text);
                    }
                }
                _ => {}
            }
            index += 1;
        }
        at = index + 1;
    }
    names
}

/// Minimum and median of 201 runs, in milliseconds.
fn time<F: FnMut()>(mut run: F) -> (f64, f64) {
    let mut samples: Vec<u128> = (0..201)
        .map(|_| {
            let start = std::time::Instant::now();
            run();
            start.elapsed().as_nanos()
        })
        .collect();
    samples.sort_unstable();
    (samples[0] as f64 / 1e6, samples[samples.len() / 2] as f64 / 1e6)
}

/// Host latency: `rustc -O --test`, then `--ignored bench --nocapture`.
#[test]
#[ignore]
fn bench() {
    let vocabulary = [
        "the", "we", "moved", "our", "notes", "to", "a", "new", "server", "and", "then", "it",
        "worked", "after", "short", "while", "but", "nobody", "noticed", "today", "really",
        "about", "build", "release", "meeting", "should", "would", "could", "people", "because",
        "there", "their", "they", "said", "think", "maybe", "next", "week", "phone", "message",
        "change", "settings", "before", "morning", "evening", "write", "letter", "number", "little",
        "important", "question", "answer", "problem", "picture", "together", "remember", "around",
        "between", "different", "family", "children", "nothing", "something", "everyone", "always",
        "never", "again", "still", "office", "travel", "kitchen", "window", "garden", "water",
        "store", "coin", "master", "stir", "tail", "scale", "parquet", "node", "zap", "jaylin",
    ];
    let mut random = Lcg(3);
    let mut words = |count: usize| -> String {
        let words: Vec<&str> = (0..count).map(|_| vocabulary[random.next(vocabulary.len())]).collect();
        words.join(" ")
    };
    let ordinary = words(2000);
    let long = words(10_000);
    let collision: Vec<&str> = (0..2000)
        .map(|index| ["tail", "scale", "jaylin", "no", "stir", "cotlin"][index % 6])
        .collect();
    let collision = collision.join(" ");
    let names = [
        "Nostr", "Tailscale", "Zapstore", "Parakeet", "Bitcoin", "Kotlin", "Amethyst", "Marmot",
        "Jaylen", "Jaelyn",
    ];
    let term_list = |count: usize| -> Vec<String> {
        (0..count)
            .map(|index| match names.get(index) {
                Some(name) => name.to_string(),
                None => format!("Brandname{}", ["x", "q", "z", "v"][index % 4].repeat(1 + index / 4)),
            })
            .collect()
    };
    for (label, text) in [("2k words", &ordinary), ("10k words", &long), ("2k collision", &collision)] {
        for count in [0, 20, 128] {
            let written = term_list(count);
            let (before_min, before) = time(|| {
                std::hint::black_box(baseline(text, &written, &[]));
            });
            let (after_min, after) = time(|| {
                std::hint::black_box(preferred_terms::apply_with_sound_alike(
                    text, &written, &[], ENGLISH, &[],
                ));
            });
            println!(
                "{label}, {count} terms: baseline min/p50 {before_min:.3}/{before:.3} ms, \
                 sound-alike min/p50 {after_min:.3}/{after:.3} ms, delta min/p50 {:.3}/{:.3} ms",
                after_min - before_min,
                after - before,
            );
        }
    }
    // The whole cleanup, as shipped, against the baseline cleanup.
    for (label, text) in [("2k words", &ordinary), ("2k collision", &collision)] {
        for (mode, language) in [("English", OutputLanguage::English), ("Automatic", OutputLanguage::Unknown)] {
            for count in [20, 128] {
                let written = term_list(count);
                let (before_min, before) = time(|| {
                    let (text, protected) = baseline(text, &written, &[]);
                    let english = language == OutputLanguage::English || english_fillers::looks_english(&text);
                    std::hint::black_box(if english {
                        english_fillers::remove_with_protected_owned(text, &protected)
                    } else {
                        text
                    });
                });
                let (after_min, after) = time(|| {
                    std::hint::black_box(transcript_cleanup::finish(text.to_string(), language, Some(&written), None));
                });
                println!(
                    "finish {mode}, {label}, {count} terms: baseline min/p50 {before_min:.3}/{before:.3} ms, \
                     sound-alike min/p50 {after_min:.3}/{after:.3} ms, delta min/p50 {:.3}/{:.3} ms",
                    after_min - before_min,
                    after - before,
                );
            }
        }
    }
}
