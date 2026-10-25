#[path = "../src/english_fillers.rs"]
mod english_fillers;
#[path = "../src/personal_corrections.rs"]
mod personal_corrections;
#[path = "../src/preferred_terms.rs"]
mod preferred_terms;
#[path = "../src/transcript_cleanup.rs"]
mod transcript_cleanup;
#[path = "../src/transcript_guard.rs"]
mod transcript_guard;

#[test]
fn final_cleanup_preserves_explicit_intent_and_remaps_changed_offsets() {
    let rules =
        personal_corrections::parse("uh huh => acknowledgement\nintent => Um\nbrand => iOS\n")
            .unwrap();
    let terms = vec!["Parakeet".to_string(), "UM".to_string()];
    let finish = |text: &str, english| {
        transcript_cleanup::finish(text.to_string(), english, Some(&terms), Some(&rules))
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
        transcript_cleanup::finish(raw.to_string(), true, None, None),
        "`uh` and folder/um are literal"
    );
}

#[test]
fn earlier_length_changing_preferred_names_preserve_later_correction_ranges() {
    let rules = personal_corrections::parse("intent => Uh um\n").unwrap();
    let terms = vec!["npub".to_string()];
    let text = "en pop intent uh finish";
    assert_eq!(
        transcript_cleanup::finish(text.to_string(), true, Some(&terms), Some(&rules)),
        "npub Uh um finish"
    );
}

#[test]
fn corrections_match_raw_text_without_filler_induced_rule_chaining() {
    let rules = personal_corrections::parse("alpha beta => intended\n").unwrap();
    assert_eq!(
        transcript_cleanup::finish("alpha uh beta".to_string(), true, None, Some(&rules)),
        "alpha beta"
    );
}

#[test]
fn clearly_english_text_is_cleaned_when_the_model_reports_no_language() {
    let text = "And he also has some kind of training for flow state for the masses. Uh look it up and tell me what it is.";
    assert_eq!(
        transcript_cleanup::finish(text.to_string(), false, None, None),
        "And he also has some kind of training for flow state for the masses. Look it up and tell me what it is."
    );
    // Not enough evidence of English: leave `uh`/`um` alone.
    assert_eq!(
        transcript_cleanup::finish("Das ist um zehn Uhr".to_string(), false, None, None),
        "Das ist um zehn Uhr"
    );
}
