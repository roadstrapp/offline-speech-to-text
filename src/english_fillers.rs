//! Small, conservative final-text cleanup for known-English dictation.
//!
//! Do not run this on an unknown-language transcript: `uh` and `um` can be meaningful
//! elsewhere, and the recognizer may not report a language in automatic mode.

use crate::transcript_guard::ProtectedText;
use std::ops::Range;

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

#[cfg(test)]
pub(crate) fn remove_fillers_owned(text: String) -> String {
    remove_with_protected_owned(text, &[])
}

pub(crate) fn remove_with_protected_owned(text: String, protected: &[Range<usize>]) -> String {
    if !text
        .as_bytes()
        .windows(2)
        .any(|pair| pair.eq_ignore_ascii_case(b"uh") || pair.eq_ignore_ascii_case(b"um"))
    {
        return text;
    }
    let cleaned = remove_with_protected(&text, protected);
    if cleaned == text {
        text
    } else {
        cleaned
    }
}

#[cfg(test)]
pub(crate) fn remove_fillers(text: &str) -> String {
    remove_with_protected(text, &[])
}

fn remove_with_protected(text: &str, protected: &[Range<usize>]) -> String {
    let mut output = String::with_capacity(text.len());
    let mut copy_from = 0;
    let mut has_prefix_content = false;
    let mut removed_initial_capitalized_filler = false;
    let guard = ProtectedText::new(text, protected);
    let mut can_capitalize_next = true;

    for (start, character) in text.char_indices() {
        if start < copy_from || !matches!(character, 'u' | 'U') {
            continue;
        }
        let Some(word) = text.get(start..start + 2) else {
            continue;
        };
        if matches!(word, "uh" | "Uh" | "um" | "Um")
            && is_standalone(text, start, start + 2)
            && guard.allows(start, start + 2)
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
            if !has_prefix_content {
                can_capitalize_next = text[end..]
                    .char_indices()
                    .find(|(_, next)| !next.is_whitespace())
                    .is_none_or(|(relative, next)| {
                        guard.allows(end + relative, end + relative + next.len_utf8())
                    });
            }
            copy_from = end;
        }
    }
    output.push_str(&text[copy_from..]);
    if removed_initial_capitalized_filler && can_capitalize_next {
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
    use super::{known_english_output, remove_fillers, remove_fillers_owned};

    #[test]
    fn unchanged_text_reuses_the_original_allocation() {
        let original = String::from("A different sentence.");
        let original_pointer = original.as_ptr();
        let cleaned = remove_fillers_owned(original);
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
        assert_eq!(remove_fillers("I uh think so"), "I think so");
        assert_eq!(remove_fillers("Well, uh, I agree"), "Well, I agree");
        assert_eq!(remove_fillers("Uh, we should go"), "We should go");
        assert_eq!(remove_fillers("I, uh, think so"), "I, think so");
        assert_eq!(remove_fillers("I think uh."), "I think.");
        assert_eq!(remove_fillers("I said uh: maybe"), "I said: maybe");
        assert_eq!(remove_fillers("I, uh. Okay"), "I. Okay");
        assert_eq!(remove_fillers("Uh. We should go"), "We should go");
        assert_eq!(remove_fillers("Uh... we should go"), "We should go");
        assert_eq!(remove_fillers("Uh, über alles"), "Über alles");
        assert_eq!(remove_fillers("I uh uh agree"), "I agree");
        assert_eq!(remove_fillers("I (uh) think"), "I think");
        assert_eq!(remove_fillers("I [uh] think"), "I think");
        assert_eq!(remove_fillers("I UH agree"), "I UH agree");
        assert_eq!(remove_fillers("I uh\nthink so"), "I\nthink so");
    }

    #[test]
    fn removes_um_without_changing_literal_or_meaningful_tokens() {
        assert_eq!(remove_fillers("Um, we um should uh go."), "We should go.");
        assert_eq!(remove_fillers("I (um) think"), "I think");
        for text in [
            "um",
            "Um, uh.",
            "umbrella hum umber",
            "um-hum",
            "'um'",
            "`uh um`",
            "Use `uh` and `um` here",
            "https://example.org/um",
            "folder/um",
            "um@example.org",
            "I went to UM last year",
            "University (UM) and UH",
            "a ` stray um and uh",
        ] {
            assert_eq!(remove_fillers(text), text);
        }
    }

    #[test]
    fn preserves_words_names_quotes_and_addresses() {
        let text = "huh uh-oh Uhuru 'uh' “uh” https://example.org/uh user@uh.example uh.example example.org/uh folder/uh";
        assert_eq!(remove_fillers(text), text);
        assert_eq!(
            remove_fillers("She said \"uh, maybe\""),
            "She said \"uh, maybe\""
        );
        assert_eq!(remove_fillers("uh's meaning"), "uh's meaning");
        assert_eq!(remove_fillers("uh’s meaning"), "uh’s meaning");
        assert_eq!(remove_fillers("uh–huh"), "uh–huh");
        assert_eq!(remove_fillers("Uhr umher"), "Uhr umher");
    }

    #[test]
    fn never_turns_a_complete_recognition_into_empty_text() {
        assert_eq!(remove_fillers("uh"), "uh");
        assert_eq!(remove_fillers("Uh, uh."), "Uh, uh.");
    }
}
