//! Small, conservative final-text cleanup for known-English dictation.
//!
//! Do not run this on an unknown-language transcript: `uh` can be meaningful
//! elsewhere, and the recognizer may not report a language in automatic mode.

pub(crate) fn is_english_tag(tag: &str) -> bool {
    tag.eq_ignore_ascii_case("en")
        || tag.get(..3).is_some_and(|prefix| {
            prefix.eq_ignore_ascii_case("en-") || prefix.eq_ignore_ascii_case("en_")
        })
}

pub(crate) fn known_english_output(
    detected: Option<&str>,
    hint: Option<&str>,
    english_only_model: bool,
    translates_to_english: bool,
) -> bool {
    translates_to_english
        || detected
            .filter(|language| !language.is_empty())
            .or(hint)
            .map(is_english_tag)
            .unwrap_or(english_only_model)
}

pub(crate) fn remove_uh_owned(text: String) -> String {
    if !text
        .as_bytes()
        .windows(2)
        .any(|pair| pair.eq_ignore_ascii_case(b"uh"))
    {
        return text;
    }
    let cleaned = remove_uh(&text);
    if cleaned == text {
        text
    } else {
        cleaned
    }
}

pub(crate) fn remove_uh(text: &str) -> String {
    let mut output = String::with_capacity(text.len());
    let mut copy_from = 0;
    let mut has_prefix_content = false;
    let mut removed_initial_capitalized_filler = false;
    let mut double_quote = false;
    let mut single_quote = false;

    for (start, character) in text.char_indices() {
        if start < copy_from {
            continue;
        }
        match character {
            '"' => double_quote = !double_quote,
            '“' => double_quote = true,
            '”' => double_quote = false,
            '‘' => single_quote = true,
            '’' if !apostrophe_inside_word(text, start, character.len_utf8()) => {
                single_quote = false
            }
            '\'' if !apostrophe_inside_word(text, start, character.len_utf8()) => {
                single_quote = !single_quote
            }
            _ => {}
        }
        if double_quote || single_quote || !matches!(character, 'u' | 'U') {
            continue;
        }
        let Some(word) = text.get(start..start + 2) else {
            continue;
        };
        if word.eq_ignore_ascii_case("UH")
            && is_standalone(text, start, start + 2)
            && !is_protected_token(text, start)
        {
            let paired_brackets = (text[..start].ends_with('(')
                && text[start + 2..].starts_with(')'))
                || (text[..start].ends_with('[') && text[start + 2..].starts_with(']'));
            let preceding = &text[copy_from..if paired_brackets { start - 1 } else { start }];
            has_prefix_content |= preceding.chars().any(|part| !part.is_whitespace());
            if !has_prefix_content && character == 'U' {
                removed_initial_capitalized_filler = true;
            }
            output.push_str(preceding);
            let mut end = start + 2;
            if paired_brackets {
                end += 1;
            }
            if text[end..].starts_with(',') {
                end += 1;
            }
            let following = text[end..].chars().next();
            if following.is_none() || following.is_some_and(is_tight_punctuation) {
                trim_end_space(&mut output);
                if following.is_some_and(is_tight_punctuation) && output.ends_with(',') {
                    output.pop();
                }
            } else if following.is_some_and(is_line_break) {
                trim_end_inline_space(&mut output);
            }
            if !has_prefix_content {
                // A leading "Uh..." is a filler sentence, not leftover
                // punctuation before the next word. Never return an empty
                // transcript, though.
                while let Some(mark) = text[end..]
                    .chars()
                    .next()
                    .filter(|mark| is_tight_punctuation(*mark))
                {
                    end += mark.len_utf8();
                }
            }
            if !has_prefix_content || output.chars().next_back().is_some_and(char::is_whitespace) {
                while text[end..].starts_with(is_inline_space) {
                    end += text[end..].chars().next().unwrap().len_utf8();
                }
            }
            copy_from = end;
        }
    }
    output.push_str(&text[copy_from..]);
    if removed_initial_capitalized_filler {
        // The recognizer capitalizes a filler at the beginning of a sentence.
        // Preserve that sentence case when the filler itself disappears.
        if let Some((index, first)) = output
            .char_indices()
            .find(|(_, character)| !character.is_whitespace())
        {
            let uppercase = first.to_uppercase().collect::<String>();
            output.replace_range(index..index + first.len_utf8(), &uppercase);
        }
    }
    if output.chars().any(char::is_alphanumeric) {
        output
    } else {
        // A sole filler is still a recognized utterance. Returning an empty
        // string would be mistaken for failed recognition by some callers.
        text.to_string()
    }
}

fn is_standalone(text: &str, start: usize, end: usize) -> bool {
    let before = text[..start].chars().next_back();
    let after = text[end..].chars().next();
    !before.is_some_and(is_word_or_hyphen)
        && !after.is_some_and(is_word_or_hyphen)
        && !(after.is_some_and(|character| matches!(character, '\'' | '’'))
            && text[end + after.unwrap().len_utf8()..]
                .chars()
                .next()
                .is_some_and(char::is_alphanumeric))
}

fn is_word_or_hyphen(character: char) -> bool {
    character.is_alphanumeric() || matches!(character, '_' | '-' | '‐' | '‑' | '–' | '—')
}

fn apostrophe_inside_word(text: &str, index: usize, character_bytes: usize) -> bool {
    text[..index]
        .chars()
        .next_back()
        .is_some_and(char::is_alphanumeric)
        && text[index + character_bytes..]
            .chars()
            .next()
            .is_some_and(char::is_alphanumeric)
}

fn is_protected_token(text: &str, index: usize) -> bool {
    let start = text[..index].rfind(char::is_whitespace).map_or(0, |space| {
        space + text[space..].chars().next().unwrap().len_utf8()
    });
    let end = text[index..]
        .find(char::is_whitespace)
        .map_or(text.len(), |space| index + space);
    let token = &text[start..end];
    token.contains("://")
        || token.contains('@')
        || token.contains('/')
        || token.contains('\\')
        || token.char_indices().any(|(index, character)| {
            character == '.'
                && token[..index]
                    .chars()
                    .next_back()
                    .is_some_and(char::is_alphanumeric)
                && token[index + 1..]
                    .chars()
                    .next()
                    .is_some_and(char::is_alphanumeric)
        })
        || token
            .get(..4)
            .is_some_and(|prefix| prefix.eq_ignore_ascii_case("www."))
}

fn is_tight_punctuation(character: char) -> bool {
    matches!(character, '.' | '?' | '!' | ':' | ';' | '…')
}

fn is_line_break(character: char) -> bool {
    matches!(character, '\n' | '\r' | '\u{2028}' | '\u{2029}')
}

fn is_inline_space(character: char) -> bool {
    character.is_whitespace() && !is_line_break(character)
}

fn trim_end_space(output: &mut String) {
    output.truncate(output.trim_end_matches(char::is_whitespace).len());
}

fn trim_end_inline_space(output: &mut String) {
    output.truncate(output.trim_end_matches(is_inline_space).len());
}

#[cfg(test)]
mod tests {
    use super::{known_english_output, remove_uh, remove_uh_owned};

    #[test]
    fn unchanged_text_reuses_the_original_allocation() {
        let original = String::from("A different sentence.");
        let original_pointer = original.as_ptr();
        let cleaned = remove_uh_owned(original);
        assert_eq!(cleaned, "A different sentence.");
        assert_eq!(cleaned.as_ptr(), original_pointer);
    }

    #[test]
    fn cleanup_requires_a_known_english_output() {
        assert!(known_english_output(Some("en"), None, false, false));
        assert!(known_english_output(None, Some("en-US"), false, false));
        assert!(known_english_output(None, Some("EN_gb"), false, false));
        assert!(known_english_output(None, None, true, false));
        assert!(known_english_output(Some(""), Some("en"), false, false));
        assert!(known_english_output(Some("de"), None, false, true));
        assert!(!known_english_output(None, None, false, false));
        assert!(!known_english_output(Some("de"), Some("en"), false, false));
        assert!(!known_english_output(None, Some("fr-FR"), true, false));
    }

    #[test]
    fn removes_standalone_filler_and_its_comma() {
        assert_eq!(remove_uh("I uh think so"), "I think so");
        assert_eq!(remove_uh("Well, uh, I agree"), "Well, I agree");
        assert_eq!(remove_uh("Uh, we should go"), "We should go");
        assert_eq!(remove_uh("I, uh, think so"), "I, think so");
        assert_eq!(remove_uh("I think uh."), "I think.");
        assert_eq!(remove_uh("I said uh: maybe"), "I said: maybe");
        assert_eq!(remove_uh("I, uh. Okay"), "I. Okay");
        assert_eq!(remove_uh("Uh. We should go"), "We should go");
        assert_eq!(remove_uh("Uh... we should go"), "We should go");
        assert_eq!(remove_uh("Uh, über alles"), "Über alles");
        assert_eq!(remove_uh("I uh uh agree"), "I agree");
        assert_eq!(remove_uh("I (uh) think"), "I think");
        assert_eq!(remove_uh("I [uh] think"), "I think");
        assert_eq!(remove_uh("I UH agree"), "I agree");
        assert_eq!(remove_uh("I uh\nthink so"), "I\nthink so");
    }

    #[test]
    fn preserves_words_names_quotes_and_addresses() {
        let text = "huh uh-oh Uhuru 'uh' “uh” https://example.org/uh user@uh.example uh.example example.org/uh folder/uh";
        assert_eq!(remove_uh(text), text);
        assert_eq!(
            remove_uh("She said \"uh, maybe\""),
            "She said \"uh, maybe\""
        );
        assert_eq!(remove_uh("uh's meaning"), "uh's meaning");
        assert_eq!(remove_uh("uh’s meaning"), "uh’s meaning");
        assert_eq!(remove_uh("uh–huh"), "uh–huh");
        assert_eq!(remove_uh("Uhr umher"), "Uhr umher");
    }

    #[test]
    fn never_turns_a_complete_recognition_into_empty_text() {
        assert_eq!(remove_uh("uh"), "uh");
        assert_eq!(remove_uh("Uh, uh."), "Uh, uh.");
    }
}
