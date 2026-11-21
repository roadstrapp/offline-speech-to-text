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

#[test]
fn one_loaded_list_keeps_prompt_and_cleanup_consistent_across_a_save() {
    let root = std::env::temp_dir().join(format!("ostt-snapshot-{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    let file = root.join("custom_terms");
    std::fs::write(&file, "Before\n").unwrap();
    let snapshot = custom_terms::load_terms(&file).unwrap();
    std::fs::write(&file, "After\n").unwrap();
    assert_eq!(
        custom_terms::compile_prompt(&snapshot).as_deref(),
        Some("Before")
    );
    assert_eq!(snapshot, vec!["Before"]);
    assert_eq!(custom_terms::load_prompt(&file).as_deref(), Some("After"));
    std::fs::write(&file, [0xff]).unwrap();
    assert!(custom_terms::load_terms(&file).is_none());
    std::fs::remove_dir_all(root).unwrap();
}

fn request(words: &[&str]) -> Vec<String> {
    custom_terms::parse_request_terms(&words.join("\n"))
}

#[test]
fn request_words_are_cleaned_entry_by_entry_in_the_callers_order() {
    let long = "x".repeat(81);
    let words = custom_terms::parse_request_terms(&format!(
        "  Droneboard \n\nZapstore\tRelay\ndroneboard\n<|notimestamps|>\nbad\u{7}bell\n{long}\nKošice\n"
    ));
    // Trimmed, inner whitespace collapsed, duplicates folded; unusable entries dropped alone.
    assert_eq!(words, ["Droneboard", "Zapstore Relay", "Košice"]);
    assert!(custom_terms::parse_request_terms("").is_empty());
    assert!(custom_terms::parse_request_terms(" \n\t\n").is_empty());
}

#[test]
fn request_words_are_bounded_by_count_and_total_bytes() {
    let many = (0..5000).map(|index| format!("word{index}")).collect::<Vec<_>>().join("\n");
    let words = custom_terms::parse_request_terms(&many);
    assert_eq!(words.len(), custom_terms::MAX_REQUEST_TERMS);
    // The first words are the ones kept.
    assert_eq!(words[0], "word0");
    assert_eq!(words[999], "word999");
    let wide = (0..1000).map(|index| format!("{index:04}{}", "é".repeat(38))).collect::<Vec<_>>().join("\n");
    let words = custom_terms::parse_request_terms(&wide);
    let bytes: usize = words.iter().map(String::len).sum();
    assert!(bytes <= custom_terms::MAX_REQUEST_BYTES);
    assert_eq!(words.len(), custom_terms::MAX_REQUEST_BYTES / 80);
    assert!(words[0].starts_with("0000"));
}

#[test]
fn request_words_fill_only_the_prompt_space_the_users_words_leave() {
    let own = vec!["Nostr".to_string(), "HeliBoard".to_string()];
    let prompt = custom_terms::compile_request_prompt(Some(&own), &request(&["Droneboard", "nostr", "Zapstore"]));
    assert_eq!(prompt.as_deref(), Some("Nostr, HeliBoard, Droneboard, Zapstore"));
    // Without request words the prompt is exactly the user's.
    assert_eq!(
        custom_terms::compile_request_prompt(Some(&own), &[]),
        custom_terms::compile_prompt(&own)
    );
    assert_eq!(custom_terms::compile_request_prompt(None, &[]), None);
    assert_eq!(
        custom_terms::compile_request_prompt(None, &request(&["Droneboard"])).as_deref(),
        Some("Droneboard")
    );
    // A full list of the user's keeps every one of its words; the request gets nothing.
    let full: Vec<String> = (0..3).map(|index| format!("{index}{}", "a".repeat(64))).collect();
    let alone = custom_terms::compile_prompt(&full).unwrap();
    assert_eq!(alone.len(), 199);
    assert_eq!(
        custom_terms::compile_request_prompt(Some(&full), &request(&["Droneboard"])).as_deref(),
        Some(alone.as_str())
    );
    // Caller order is priority: the prompt stops at the first word that does not fit.
    let thousand: Vec<String> = (0..1000).map(|index| format!("name{index}")).collect();
    let prompt = custom_terms::compile_request_prompt(None, &thousand).unwrap();
    assert!(prompt.len() <= 200);
    assert!(prompt.starts_with("name0, name1, name2"));
    assert!(!prompt.contains("name999"));
}
