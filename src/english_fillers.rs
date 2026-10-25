//! Small, conservative final-text cleanup for English dictation.
//!
//! `uh` and `um` can be meaningful elsewhere, so cleanup runs only when the output is known
//! to be English. Many models (Parakeet v3 in automatic mode, for one) do not report a
//! language, so [`looks_english`] lets clearly English text qualify on its own evidence.

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

/// Words that are English and nothing else in the other languages the supported models
/// transcribe. Deliberately omits look-alikes (`was`, `is`, `also`, `will`, `had`, `to`, ...).
const ENGLISH_MARKERS: &[&str] = &[
    "the", "and", "of", "it", "up", "that", "this", "with", "have", "has", "for", "not", "but",
    "you", "your", "what", "which", "there", "their", "they", "would", "could", "should", "about",
    "from", "some", "then", "when", "been", "because", "look", "tell", "know", "think", "like",
    "here", "are", "were", "going", "really", "right",
];

/// Whether the transcript itself is clearly English: at least two English-only function words
/// making up a quarter of its words. Short or mixed-language text deliberately does not qualify.
pub(crate) fn looks_english(text: &str) -> bool {
    let (mut words, mut markers) = (0usize, 0usize);
    for word in text
        .split(|character: char| !(character.is_alphabetic() || character == '\''))
        .filter(|word| !word.is_empty())
    {
        words += 1;
        if ENGLISH_MARKERS
            .iter()
            .any(|marker| word.eq_ignore_ascii_case(marker))
        {
            markers += 1;
        }
    }
    markers >= 2 && markers * 4 >= words
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
    // Output offset and source offset of the word following a capitalized filler that began a
    // sentence; that word inherits the sentence case once the filler disappears.
    let mut recapitalize: Vec<(usize, usize)> = Vec::new();
    let guard = ProtectedText::new(text, protected);

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
            output.push_str(preceding);
            let starts_sentence = character == 'U'
                && output
                    .trim_end_matches(is_inline_space)
                    .chars()
                    .next_back()
                    .is_none_or(|previous| is_tight_punctuation(previous) || is_line_break(previous));
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
            if starts_sentence {
                recapitalize.push((output.len(), end));
            }
            copy_from = end;
        }
    }
    output.push_str(&text[copy_from..]);
    for (offset, source) in recapitalize {
        // The recognizer capitalizes a filler at the beginning of a sentence. Preserve that
        // sentence case when the filler itself disappears.
        let Some((relative, first)) = output
            .get(offset..)
            .and_then(|rest| rest.char_indices().find(|(_, next)| !next.is_whitespace()))
        else {
            continue;
        };
        let source = source
            + text[source..]
                .find(|next: char| !next.is_whitespace())
                .unwrap_or(0);
        if first.is_lowercase() && guard.allows(source, source + first.len_utf8()) {
            let at = offset + relative;
            let uppercase = first.to_uppercase().collect::<String>();
            output.replace_range(at..at + first.len_utf8(), &uppercase);
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
    use super::{known_english_output, looks_english, remove_fillers, remove_fillers_owned};

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
    fn restores_sentence_case_after_a_mid_text_filler() {
        assert_eq!(
            remove_fillers("Flow state for the masses. Uh look it up and tell me."),
            "Flow state for the masses. Look it up and tell me."
        );
        assert_eq!(remove_fillers("Really? Um, no."), "Really? No.");
        assert_eq!(remove_fillers("One.\nUh two"), "One.\nTwo");
        assert_eq!(remove_fillers("I think uh it works"), "I think it works");
        assert_eq!(remove_fillers("Done. uh over"), "Done. over");
    }

    #[test]
    fn clearly_english_text_qualifies_without_a_reported_language() {
        assert!(looks_english(
            "And he also has some kind of training for flow state for the masses. Uh look it up and tell me what it is."
        ));
        assert!(looks_english("Uh look it up"));
        assert!(!looks_english("I uh think so"));
        assert!(!looks_english("Um den Tisch herum, und das ist es"));
        assert!(!looks_english("Je pense que c'est um très bien, merci"));
        assert!(!looks_english("Hallo, heute ist es schön. Uh, das ist gut."));
        assert!(!looks_english(""));
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
