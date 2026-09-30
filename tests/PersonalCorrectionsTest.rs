#[path = "../src/personal_corrections.rs"]
mod personal_corrections;
#[path = "../src/transcript_guard.rs"]
mod transcript_guard;

#[test]
fn exact_rules_are_case_insensitive_bounded_and_longest_first() {
    let rules = personal_corrections::parse(
        "marmot => Marmot\nmarmot protocol => Marmot Protocol\nipf => IPF\n",
    )
    .unwrap();
    assert_eq!(
        personal_corrections::apply("marmot protocol uses ipf and MARMOT", &rules),
        "Marmot Protocol uses IPF and Marmot"
    );
}

#[test]
fn rules_do_not_chain_or_replace_inside_other_words() {
    let rules =
        personal_corrections::parse("marmot => Marmot Protocol\nprotocol => PROTOCOL\n").unwrap();
    assert_eq!(
        personal_corrections::apply("marmot protocol marmotized", &rules),
        "Marmot Protocol PROTOCOL marmotized"
    );
}

#[test]
fn urls_emails_and_quotations_are_preserved() {
    let rules = personal_corrections::parse("marmot => Marmot\nipf => IPF\n").unwrap();
    assert_eq!(
        personal_corrections::apply(
            "marmot https://marmot.example a@ipf.dev \"marmot\" 'ipf and marmot' “ipf” ‘marmot’",
            &rules,
        ),
        "Marmot https://marmot.example a@ipf.dev \"marmot\" 'ipf and marmot' “ipf” ‘marmot’"
    );
}

#[test]
fn apostrophes_do_not_hide_following_corrections() {
    let rules = personal_corrections::parse("marmot => Marmot\nipf => IPF\n").unwrap();
    assert_eq!(
        personal_corrections::apply("I'm with marmot at James' ipf meetup", &rules),
        "I'm with Marmot at James' IPF meetup"
    );
}

#[test]
fn invalid_or_disabled_files_fail_closed() {
    assert!(personal_corrections::parse("nip 46 => NIP 47\n").is_err());
    assert!(personal_corrections::parse("marmot => \"Marmot\"\n").is_err());
    assert!(personal_corrections::parse("marmot\n").is_err());

    let root = std::env::temp_dir().join(format!(
        "ostt-personal-corrections-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir_all(&root).unwrap();
    std::fs::write(root.join(personal_corrections::FILE_NAME), "ipf => IPF\n").unwrap();
    assert_eq!(personal_corrections::load(&root), None);
    std::fs::write(root.join(personal_corrections::ENABLED_FILE_NAME), "1\n").unwrap();
    let loaded = personal_corrections::load(&root).unwrap();
    assert_eq!(personal_corrections::apply("ipf", &loaded), "IPF");
    std::fs::write(root.join(personal_corrections::FILE_NAME), "bad data\n").unwrap();
    assert_eq!(personal_corrections::load(&root), None);
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn protects_the_entire_match_and_literal_identifiers() {
    let rules =
        personal_corrections::parse("white noise => WhiteNoise\nmarmot => Marmot\n").unwrap();
    for input in [
        "white noise@example.com",
        "white noise.dev",
        "marmot.dev",
        "`marmot`",
        "white noise://host",
        "white \"noise\"",
        "‘marmot’",
    ] {
        assert_eq!(personal_corrections::apply(input, &rules), input);
    }
    assert_eq!(
        personal_corrections::apply("l‘homme uses marmot, James’ marmot", &rules),
        "l‘homme uses Marmot, James’ Marmot"
    );
}

#[test]
fn unicode_fold_expansion_and_multibyte_replacement_offsets_are_safe() {
    let rules = personal_corrections::parse("İpek => Person\nwhite noise => WN\n").unwrap();
    assert_eq!(
        personal_corrections::apply("i\u{307}pek and white noise", &rules),
        "Person and WN"
    );
    let input = "éclair white noise@example.com and white noise";
    let (text, ranges) = personal_corrections::apply_with_ranges(input, &rules);
    assert_eq!(text, "éclair white noise@example.com and WN");
    assert_eq!(&text[ranges[0].clone()], "WN");
}

#[test]
fn long_protected_tokens_and_many_quotes_remain_unchanged() {
    let rules = personal_corrections::parse("marmot => Marmot\n").unwrap();
    let input = format!(
        "https://{} {} marmot",
        "marmot".repeat(10000),
        "‘marmot’ ".repeat(2000)
    );
    let expected = format!(
        "https://{} {} Marmot",
        "marmot".repeat(10000),
        "‘marmot’ ".repeat(2000)
    );
    assert_eq!(personal_corrections::apply(&input, &rules), expected);
}
