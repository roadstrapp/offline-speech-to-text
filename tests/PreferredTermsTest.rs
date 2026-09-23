#[path = "../src/personal_corrections.rs"]
mod personal_corrections;
#[path = "../src/preferred_terms.rs"]
mod preferred_terms;

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
