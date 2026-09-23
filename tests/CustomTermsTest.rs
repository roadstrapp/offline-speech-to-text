#[path = "../src/custom_terms.rs"]
mod custom_terms;

#[test]
fn normalizes_and_deduplicates_without_changing_case() {
    let prompt =
        custom_terms::compile_prompt_text("  Nostr  \nHeliBoard\nnostr\nSatoshi   Nakamoto\n")
            .unwrap();
    assert_eq!(
        prompt.as_deref(),
        Some("Nostr, HeliBoard, Satoshi Nakamoto")
    );
}

#[test]
fn empty_input_disables_the_prompt() {
    assert_eq!(custom_terms::compile_prompt_text("\n  \n").unwrap(), None);
}

#[test]
fn rejects_special_tokens_and_oversized_input() {
    assert!(custom_terms::compile_prompt_text("safe\n<|notimestamps|>").is_err());
    assert!(custom_terms::compile_prompt_text(&"x".repeat(81)).is_err());
    let too_many = (0..129)
        .map(|index| format!("word{index}"))
        .collect::<Vec<_>>()
        .join("\n");
    assert!(custom_terms::compile_prompt_text(&too_many).is_err());
    assert_eq!(
        custom_terms::compile_prompt_text(&format!(
            "{}\n{}\n{}",
            "a".repeat(70),
            "b".repeat(70),
            "c".repeat(70)
        ))
        .unwrap(),
        Some(format!("{}, {}", "a".repeat(70), "b".repeat(70)))
    );
}

#[test]
fn file_reader_fails_closed_without_exposing_content() {
    let root = std::env::temp_dir().join(format!(
        "ostt-custom-terms-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir_all(&root).unwrap();
    let file = root.join("custom_terms");
    std::fs::write(&file, "Zapstore\nNostr\n").unwrap();
    assert_eq!(
        custom_terms::load_prompt(&file).as_deref(),
        Some("Zapstore, Nostr")
    );
    std::fs::write(&file, "<|unsafe|>\n").unwrap();
    assert_eq!(custom_terms::load_prompt(&file), None);
    assert_eq!(custom_terms::load_terms(&file), None);
    std::fs::remove_dir_all(root).unwrap();
}
