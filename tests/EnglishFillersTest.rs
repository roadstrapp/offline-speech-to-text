#[path = "../src/english_fillers.rs"]
mod english_fillers;
#[path = "../src/personal_corrections.rs"]
mod personal_corrections;
#[path = "../src/preferred_terms.rs"]
mod preferred_terms;
#[path = "../src/transcript_cleanup.rs"]
mod transcript_cleanup;
use transcript_cleanup::OutputLanguage;
#[path = "../src/transcript_guard.rs"]
mod transcript_guard;

#[test]
fn final_cleanup_preserves_explicit_intent_and_remaps_changed_offsets() {
    let rules =
        personal_corrections::parse("uh huh => acknowledgement\nintent => Um\nbrand => iOS\n")
            .unwrap();
    let terms = vec!["Parakeet".to_string(), "UM".to_string()];
    let finish = |text: &str, english| {
        transcript_cleanup::finish(text.to_string(), if english { OutputLanguage::English } else { OutputLanguage::Other }, Some(&terms), Some(&rules))
    };
    assert_eq!(
        finish("uh huh um we agree", true),
        "acknowledgement UM we agree"
    );
    assert_eq!(finish("enpop intent uh end", true), "enpop Um end");
    assert_eq!(finish("parakeat intent uh end", true), "Parakeet Um end");
    assert_eq!(finish("paraket intent uh end", true), "Parakeet Um end");
    assert_eq!(finish("Uh, brand works", true), "iOS works");
    assert_eq!(finish("Uh\nbrand works", true), "\niOS works");
    assert_eq!(finish("Uh,\nbrand works", true), "\niOS works");
    assert_eq!(finish("I uh think um so", false), "I uh think UM so");
    let raw = "um, `uh` and folder/um are literal";
    assert_eq!(
        transcript_cleanup::finish(raw.to_string(), OutputLanguage::English, None, None),
        "`uh` and folder/um are literal"
    );
}

#[test]
fn earlier_length_changing_preferred_names_preserve_later_correction_ranges() {
    let rules = personal_corrections::parse("intent => Uh um\n").unwrap();
    let terms = vec!["npub".to_string()];
    let text = "en pop intent uh finish";
    assert_eq!(
        transcript_cleanup::finish(text.to_string(), OutputLanguage::English, Some(&terms), Some(&rules)),
        "npub Uh um finish"
    );
}

#[test]
fn corrections_match_raw_text_without_filler_induced_rule_chaining() {
    let rules = personal_corrections::parse("alpha beta => intended\n").unwrap();
    assert_eq!(
        transcript_cleanup::finish("alpha uh beta".to_string(), OutputLanguage::English, None, Some(&rules)),
        "alpha beta"
    );
}

#[test]
fn clearly_english_text_is_cleaned_when_the_model_reports_no_language() {
    let text = "And he also has some kind of training for flow state for the masses. Uh look it up and tell me what it is.";
    assert_eq!(
        transcript_cleanup::finish(text.to_string(), OutputLanguage::Unknown, None, None),
        "And he also has some kind of training for flow state for the masses. Look it up and tell me what it is."
    );
    // Not enough evidence of English: leave `uh`/`um` alone.
    assert_eq!(
        transcript_cleanup::finish("Das ist um zehn Uhr".to_string(), OutputLanguage::Unknown, None, None),
        "Das ist um zehn Uhr"
    );
}

#[test]
fn non_english_language_evidence_prevents_english_guessing() {
    for text in ["Se for um problema, quando for possível", "Das Meeting um zehn is about the release and the deadline"] {
        assert_eq!(transcript_cleanup::finish(text.to_string(), OutputLanguage::Other, None, None), text);
    }
    assert_eq!(OutputLanguage::English.combine(OutputLanguage::Other), OutputLanguage::Other);
    assert_eq!(OutputLanguage::Unknown.combine(OutputLanguage::Other), OutputLanguage::Other);
}

#[test]
fn portuguese_article_is_preserved_even_when_language_is_unknown() {
    let text = "Se for um problema, quando for possível";
    assert_eq!(transcript_cleanup::finish(text.to_string(), OutputLanguage::Unknown, None, None), text);
}

#[test]
fn adjacent_fillers_do_not_recapitalize_an_explicit_correction() {
    let rules = personal_corrections::parse("brand => iOS").unwrap();
    assert_eq!(transcript_cleanup::finish("Uh um, brand works".to_string(),
        OutputLanguage::English, None, Some(&rules)), "iOS works");
}

#[test]
fn recapitalization_preserves_later_offsets_and_comma_case() {
    assert_eq!(english_fillers::remove_fillers("Uh, ǰump. Uh, next"), "J\u{030c}ump. Next");
    assert_eq!(english_fillers::remove_fillers("He said, Uh, the plan"), "He said, the plan");
}
