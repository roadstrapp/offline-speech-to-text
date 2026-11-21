#[path = "../src/personal_corrections.rs"]
mod personal_corrections;
#[path = "../src/preferred_terms.rs"]
mod preferred_terms;
#[path = "../src/transcript_guard.rs"]
mod transcript_guard;

fn apply(input: &str, terms: &[&str]) -> String {
    preferred_terms::apply(
        input,
        &terms.iter().map(|s| s.to_string()).collect::<Vec<_>>(),
    )
}

#[test]
fn fixes_case_close_spelling_and_transposition_without_source_pairs() {
    assert_eq!(
        apply(
            "helibord and parakeat use nostr",
            &["HeliBoard", "Parakeet", "Nostr"]
        ),
        "HeliBoard and Parakeet use Nostr"
    );
    assert_eq!(
        apply("parakeet parakeet", &["Parakeet"]),
        "Parakeet Parakeet"
    );
    assert_eq!(apply("Parakeet", &["Parakeet"]), "Parakeet");
    assert_eq!(apply("HeliBaord", &["HeliBoard"]), "HeliBoard");
}

#[test]
fn leaves_ambiguous_or_distant_words_and_phrase_hints_alone() {
    assert_eq!(apply("parakeat", &["Parakeet", "Parakeat"]), "Parakeat");
    assert_eq!(apply("parachute", &["Parakeet"]), "parachute");
    assert_eq!(apply("marmit", &["Marmot Protocol"]), "marmit");
    assert_eq!(apply("marmotized", &["Marmot"]), "marmotized");
    assert_eq!(apply("heliboards", &["HeliBoard"]), "heliboards");
}

#[test]
fn recovers_short_nostr_names_without_general_phonetic_rewriting() {
    let terms = &["White Noise", "Marmot", "npub", "Nostr"];
    assert_eq!(
        apply("Noster and enpop belong to Marmot on Nostr", terms),
        "Nostr and npub belong to Marmot on Nostr"
    );
    assert_eq!(apply("enpub and noster", terms), "npub and Nostr");
    assert_eq!(apply("en pop, N pub", terms), "npub, npub");
    assert_eq!(apply("M pups", terms), "npub");
    assert_eq!(apply("M pups", &["npub", "M pups"]), "M pups");
    assert_eq!(apply("M pups", &["Nostr"]), "M pups");
    assert_eq!(apply("en pop", &["npub", "En Pop"]), "En Pop");
    assert_eq!(apply("enpop", &["npub", "Enpop"]), "Enpop");
    assert_eq!(apply("Noster", &["Nostr", "Noster"]), "Noster");
    assert_eq!(
        apply(
            "The proper word is Noster, not enpob or enpops",
            &["Marmot"]
        ),
        "The proper word is Noster, not enpob or enpops"
    );
    assert_eq!(
        apply("enpob enpops import report", terms),
        "enpob enpops import report"
    );
    // A common word can be acoustically similar to a preferred name, but
    // rewriting it without decoder evidence would silently corrupt speech.
    assert_eq!(apply("mustard", terms), "mustard");
    assert_eq!(
        apply("https://enpop.dev user@noster.dev \"enpop\" enpop42", terms),
        "https://enpop.dev user@noster.dev \"enpop\" enpop42"
    );
}

#[test]
fn exact_preferred_phrases_restore_spelling_without_guessing_phrases() {
    let terms = &["White Noise", "Marmot Protocol"];
    assert_eq!(
        apply("white noise works with marmot protocol.", terms),
        "White Noise works with Marmot Protocol."
    );
    assert_eq!(
        apply("white noice and white\nnoise", terms),
        "white noice and white\nnoise"
    );
    assert_eq!(
        apply(
            "\"white noise\" https://white.noise/dev white noise@example.com",
            terms
        ),
        "\"white noise\" https://white.noise/dev white noise@example.com"
    );
    assert_eq!(apply("prewhite noise42", terms), "prewhite noise42");
}

#[test]
fn protects_quotes_urls_emails_numbers_and_repetition() {
    let terms = &["HeliBoard", "Parakeet"];
    assert_eq!(
        apply("\"helibord\" 'parakeat' “helibord” helibord", terms),
        "\"helibord\" 'parakeat' “helibord” HeliBoard"
    );
    assert_eq!(
        apply(
            "https://helibord.dev user@parakeat.dev www.helibord.dev",
            terms
        ),
        "https://helibord.dev user@parakeat.dev www.helibord.dev"
    );
    assert_eq!(
        apply("helibord.dev and helibord.", terms),
        "helibord.dev and HeliBoard."
    );
    assert_eq!(
        apply("helibord42 helibord helibord", terms),
        "helibord42 HeliBoard HeliBoard"
    );
    assert_eq!(apply("parakeat’s helibord", terms), "Parakeet’s HeliBoard");
}

#[test]
fn unicode_whitespace_does_not_panic() {
    assert_eq!(
        apply("hello\u{2003}helibord", &["HeliBoard"]),
        "hello\u{2003}HeliBoard"
    );
}

#[test]
fn reused_word_buffer_preserves_contextual_unicode_lowercase() {
    // String lowercase treats Greek final sigma differently from lowering
    // each scalar independently. Candidate matching must retain that rule.
    assert_eq!(apply("ος οσ ΟΣ Ος", &["ΟΣ"]), "ΟΣ οσ ΟΣ ΟΣ");
    assert_eq!(
        apply("parakeat ΟΣ helibord ος", &["Parakeet", "ΟΣ", "HeliBoard"]),
        "Parakeet ΟΣ HeliBoard ΟΣ"
    );
}

#[test]
fn explicit_corrections_win_over_similar_preferred_words() {
    let rules = personal_corrections::parse("helibord => HeliBord\n").unwrap();
    let (corrected, protected) =
        personal_corrections::apply_with_ranges("helibord and heliboard", &rules);
    assert_eq!(
        preferred_terms::apply_with_protected(&corrected, &["HeliBoard".to_string()], &protected),
        "HeliBord and HeliBoard"
    );
    let rules = personal_corrections::parse("hilly board => HeliBord\n").unwrap();
    let (corrected, protected) =
        personal_corrections::apply_with_ranges("éclair hilly board, helibord", &rules);
    assert_eq!(
        preferred_terms::apply_with_protected(&corrected, &["HeliBoard".to_string()], &protected),
        "éclair HeliBord, HeliBoard"
    );
    let rules = personal_corrections::parse("enpop => Enpop\n").unwrap();
    let (corrected, protected) = personal_corrections::apply_with_ranges("enpop", &rules);
    assert_eq!(
        preferred_terms::apply_with_protected(&corrected, &["npub".to_string()], &protected),
        "Enpop"
    );
    let rules = personal_corrections::parse("m pups => Many pups\n").unwrap();
    let (corrected, protected) = personal_corrections::apply_with_ranges("M pups", &rules);
    assert_eq!(
        preferred_terms::apply_with_protected(&corrected, &["npub".to_string()], &protected),
        "Many pups"
    );
    let rules = personal_corrections::parse("white noise => white-noise\n").unwrap();
    let (corrected, protected) = personal_corrections::apply_with_ranges("white noise", &rules);
    assert_eq!(
        preferred_terms::apply_with_protected(&corrected, &["White Noise".to_string()], &protected),
        "white-noise"
    );
}

#[test]
fn long_transcripts_are_processed_without_changing_unrelated_words() {
    let mut input = "ordinary words ".repeat(2000);
    input.push_str("helibord");
    let terms = (0..127)
        .map(|index| {
            format!(
                "ProjectName{}{}",
                (b'a' + (index / 26) as u8) as char,
                (b'a' + (index % 26) as u8) as char
            )
        })
        .chain(std::iter::once("HeliBoard".to_string()))
        .collect::<Vec<_>>();
    let result = preferred_terms::apply(&input, &terms);
    assert_eq!(
        result,
        format!("{}HeliBoard", "ordinary words ".repeat(2000))
    );
}

#[test]
fn long_transcripts_recover_explicit_nostr_aliases_without_rewriting_common_words() {
    let input = format!(
        "{}Noster, Marmot, and M pups work together; mustard remains mustard.",
        "ordinary words ".repeat(2000)
    );
    let expected = format!(
        "{}Nostr, Marmot, and npub work together; mustard remains mustard.",
        "ordinary words ".repeat(2000)
    );
    assert_eq!(
        apply(&input, &["White Noise", "Marmot", "npub", "Nostr"]),
        expected
    );
}

#[test]
fn many_phrases_do_not_change_unrelated_long_transcripts() {
    let input = format!("{}white noise", "ordinary words ".repeat(2000));
    let terms = (0..127)
        .map(|index| {
            format!(
                "BrandName{}{} Feature",
                (b'a' + (index / 26) as u8) as char,
                (b'a' + (index % 26) as u8) as char
            )
        })
        .chain(std::iter::once("White Noise".to_string()))
        .collect::<Vec<_>>();
    let result = preferred_terms::apply(&input, &terms);
    assert_eq!(
        result,
        format!("{}White Noise", "ordinary words ".repeat(2000))
    );
}

#[test]
fn possessives_and_curly_contractions_do_not_hide_later_words() {
    assert_eq!(
        apply("James' helibord and l‘homme uses helibord", &["HeliBoard"]),
        "James' HeliBoard and l‘homme uses HeliBoard"
    );
}

#[test]
fn restores_exact_compound_names_without_fuzzy_components() {
    let terms = &["Wi-Fi", "O’Reilly", "Jean-Luc", "Parakeet"];
    assert_eq!(
        apply("wi-fi, o’reilly and jean-luc", terms),
        "Wi-Fi, O’Reilly and Jean-Luc"
    );
    assert_eq!(
        apply("wi-fi42 o'reilly jean-lucas parakeat-like", terms),
        "wi-fi42 o'reilly jean-lucas parakeat-like"
    );
    assert_eq!(
        apply("‘o’reilly’ `wi-fi` \"jean-luc\"", terms),
        "‘o’reilly’ `wi-fi` \"jean-luc\""
    );
}

#[test]
fn spaced_quotes_and_unknown_compound_names_stay_literal() {
    for text in [
        "' helibord '",
        "‘ helibord ’",
        "name’helibord",
        "helibord’name",
        "helibord-name",
    ] {
        assert_eq!(apply(text, &["HeliBoard"]), text);
    }
    assert_eq!(apply("helibord’s", &["HeliBoard"]), "HeliBoard’s");
}

#[test]
fn mixed_letter_digit_names_restore_only_exact_whole_tokens() {
    let terms = &["Model7", "B2B", "3D", "RX-7"];
    assert_eq!(apply("model7 b2b 3d rx-7", terms), "Model7 B2B 3D RX-7");
    for text in [
        "model8 model77 b3b 4d rx-8",
        "premodel7 model7x x3d",
        "folder/model7 folder\\b2b",
        "model7.example user@b2b.example",
        "`model7 b2b`",
        "model7-variant",
    ] {
        assert_eq!(apply(text, terms), text);
    }
    assert_eq!(apply("123", &["456"]), "123");
}

#[test]
fn greek_phrases_use_the_same_contextual_lowercase_as_single_words() {
    assert_eq!(apply("ΚΌΣΜΟΣ ΝΈΟΣ", &["Κόσμος Νέος"]), "Κόσμος Νέος");
    assert_eq!(
        apply("\"ΚΌΣΜΟΣ ΝΈΟΣ\"", &["Κόσμος Νέος"]),
        "\"ΚΌΣΜΟΣ ΝΈΟΣ\""
    );
}
