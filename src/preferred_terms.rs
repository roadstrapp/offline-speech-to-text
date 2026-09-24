//! Conservative, local spelling cleanup for a preferred-word list.
//!
//! This is not Parakeet decoder bias: only a unique, very close spelling of
//! a listed single word is changed after recognition. Exact correction pairs
//! remain available for larger or ambiguous recognition errors.

use std::collections::HashSet;
use std::ops::Range;

const MAX_EXPANSION_BYTES: usize = 4096;

#[derive(Clone)]
struct Word {
    written: String,
    folded: Vec<char>,
}

struct Phrase {
    written: String,
    folded: String,
    char_count: usize,
    is_alias: bool,
}

#[cfg(test)]
pub(crate) fn apply(text: &str, terms: &[String]) -> String {
    apply_with_protected(text, terms, &[])
}

pub(crate) fn apply_with_protected(
    text: &str,
    terms: &[String],
    protected: &[Range<usize>],
) -> String {
    if text.is_empty() || terms.is_empty() {
        return text.to_string();
    }
    let mut words = Vec::new();
    let mut phrases = Vec::new();
    let mut seen = HashSet::new();
    for term in terms {
        let folded = term.to_lowercase();
        if !seen.insert(folded.clone()) {
            continue;
        }
        if term.chars().all(char::is_alphabetic) {
            words.push(Word {
                written: term.clone(),
                folded: folded.chars().collect(),
            });
            if folded == "npub" {
                // Decoder output may split the spoken prefix into two words.
                // Keep these forms tied to the user's explicit npub entry.
                for heard in ["en pub", "en pop", "n pub", "n pop", "m pups"] {
                    phrases.push(Phrase {
                        written: term.clone(),
                        folded: heard.to_string(),
                        char_count: heard.chars().count(),
                        is_alias: true,
                    });
                }
            }
        } else if term.contains(' ')
            && term.split(' ').count() >= 2
            && term
                .split(' ')
                .all(|part| !part.is_empty() && part.chars().all(char::is_alphabetic))
        {
            // Exact multiword spelling/case is safe to restore without
            // guessing whether an unrelated phrase was spoken.
            phrases.push(Phrase {
                written: term.clone(),
                char_count: term.chars().count(),
                folded,
                is_alias: false,
            });
        }
    }
    if words.is_empty() && phrases.is_empty() {
        return text.to_string();
    }
    // Longer exact phrases win; a user's exact phrase always beats a known
    // spoken alias of the same length.
    phrases.sort_by_key(|phrase| (std::cmp::Reverse(phrase.char_count), phrase.is_alias));

    let mut output = String::with_capacity(text.len());
    let mut offset = 0;
    let mut token_start = 0;
    let mut protected_index = 0;
    let mut double_quote = false;
    let mut single_quote = false;
    while offset < text.len() {
        let ch = text[offset..].chars().next().unwrap();
        if matches!(ch, '"' | '“' | '”') {
            double_quote = if ch == '“' {
                true
            } else if ch == '”' {
                false
            } else {
                !double_quote
            };
        } else if matches!(ch, '\'' | '‘' | '’') && !apostrophe_inside_word(text, offset, ch) {
            single_quote = if ch == '‘' {
                true
            } else if ch == '’' {
                false
            } else {
                !single_quote
            };
        }
        if !ch.is_alphabetic() {
            output.push(ch);
            offset += ch.len_utf8();
            if ch.is_whitespace() {
                token_start = offset;
            }
            continue;
        }
        let start = offset;
        while offset < text.len() {
            let next = text[offset..].chars().next().unwrap();
            if !next.is_alphabetic() {
                break;
            }
            offset += next.len_utf8();
        }
        let candidate = &text[start..offset];
        while protected_index < protected.len() && protected[protected_index].end <= start {
            protected_index += 1;
        }
        let explicit_replacement = protected
            .get(protected_index)
            .is_some_and(|range| range.start < offset);
        let joined_to_other_token = text[..start]
            .chars()
            .next_back()
            .is_some_and(|value| value.is_alphanumeric() || value == '_')
            || text[offset..]
                .chars()
                .next()
                .is_some_and(|value| value.is_alphanumeric() || value == '_');
        if double_quote
            || single_quote
            || explicit_replacement
            || joined_to_other_token
            || protected_token(text, token_start)
        {
            output.push_str(candidate);
            continue;
        }
        if let Some((phrase_end, written)) =
            match_phrase(text, start, &phrases, protected.get(protected_index))
        {
            output.push_str(written);
            if output.len() > text.len().saturating_add(MAX_EXPANSION_BYTES) {
                return text.to_string();
            }
            token_start = text[start..phrase_end]
                .rfind(' ')
                .map_or(token_start, |relative| start + relative + 1);
            offset = phrase_end;
            continue;
        }
        let folded: Vec<char> = candidate.to_lowercase().chars().collect();
        let mut exact = None;
        let mut nearby = None;
        let mut ambiguous = false;
        for word in &words {
            if word.folded == folded {
                exact = Some(word.written.as_str());
                break;
            }
            // A few spoken identifier forms are too different from their
            // spelling for edit distance to recover. Keep these aliases
            // explicit and conditional on the user listing that identifier;
            // a general phonetic rewrite would change ordinary words.
            if known_spoken_alias(&folded, &word.folded) {
                if nearby.is_some() {
                    ambiguous = true;
                } else {
                    nearby = Some(word.written.as_str());
                }
                continue;
            }
            // Three common leading letters and the same ending make a one-
            // edit typo a useful guess without treating short ordinary words
            // as interchangeable. Five-letter preferred names can recover
            // an inserted vowel (Noster -> Nostr); shorter words still need
            // an explicit spoken alias. Never change grammatical suffixes.
            if word.folded.len() < 5
                || folded.len() < 5
                || word.folded.get(..3) != folded.get(..3)
                || word.folded.last() != folded.last()
                || word.folded.len().abs_diff(folded.len()) > 1
                || !one_edit_away(&word.folded, &folded)
            {
                continue;
            }
            if nearby.is_some() {
                ambiguous = true;
            } else {
                nearby = Some(word.written.as_str());
            }
        }
        output.push_str(
            exact
                .or(if ambiguous { None } else { nearby })
                .unwrap_or(candidate),
        );
        if output.len() > text.len().saturating_add(MAX_EXPANSION_BYTES) {
            return text.to_string();
        }
    }
    output
}

fn match_phrase<'a>(
    text: &str,
    start: usize,
    phrases: &'a [Phrase],
    next_protected: Option<&Range<usize>>,
) -> Option<(usize, &'a str)> {
    for phrase in phrases {
        let tail = &text[start..];
        if tail.len() < phrase.char_count {
            continue;
        }
        let first = tail.chars().next()?;
        let expected_first = phrase.folded.chars().next()?;
        if first.is_ascii()
            && expected_first.is_ascii()
            && !first.eq_ignore_ascii_case(&expected_first)
        {
            continue;
        }
        let mut end = 0;
        let mut seen = 0;
        for (relative, ch) in tail.char_indices() {
            if seen == phrase.char_count {
                break;
            }
            seen += 1;
            end = relative + ch.len_utf8();
        }
        if seen != phrase.char_count
            || text[start..start + end].to_lowercase() != phrase.folded
            || text[start + end..]
                .chars()
                .next()
                .is_some_and(|ch| ch.is_alphanumeric() || ch == '_')
            || next_protected.is_some_and(|range| range.start < start + end)
            || phrase_has_protected_token(text, start, start + end)
        {
            continue;
        }
        return Some((start + end, phrase.written.as_str()));
    }
    None
}

fn phrase_has_protected_token(text: &str, start: usize, end: usize) -> bool {
    if protected_token(text, start) {
        return true;
    }
    text[start..end]
        .match_indices(' ')
        .any(|(relative, _)| protected_token(text, start + relative + 1))
}

fn known_spoken_alias(candidate: &[char], preferred: &[char]) -> bool {
    // Speech models may render the name of the Nostr public-key prefix as
    // "enpub" or "enpop". These are whole-token aliases, not a general
    // English sound-alike rule. They apply only when npub is in the user's
    // private preferred-word list and only outside protected text.
    preferred == ['n', 'p', 'u', 'b']
        && (candidate == ['e', 'n', 'p', 'u', 'b'] || candidate == ['e', 'n', 'p', 'o', 'p'])
}

fn one_edit_away(a: &[char], b: &[char]) -> bool {
    if a == b || a.len().abs_diff(b.len()) > 1 {
        return false;
    }
    if a.len() == b.len() {
        let mut first = None;
        let mut second = None;
        for index in 0..a.len() {
            if a[index] != b[index] {
                if first.is_none() {
                    first = Some(index);
                } else if second.is_none() {
                    second = Some(index);
                } else {
                    return false;
                }
            }
        }
        return match (first, second) {
            (Some(_), None) => true,
            (Some(left), Some(right)) => {
                right == left + 1 && a[left] == b[right] && a[right] == b[left]
            }
            _ => false,
        };
    }
    let (long, short) = if a.len() > b.len() { (a, b) } else { (b, a) };
    let mut i = 0;
    let mut j = 0;
    let mut skipped = false;
    while i < long.len() && j < short.len() {
        if long[i] == short[j] {
            i += 1;
            j += 1;
        } else if skipped {
            return false;
        } else {
            skipped = true;
            i += 1;
        }
    }
    true
}

fn apostrophe_inside_word(text: &str, offset: usize, ch: char) -> bool {
    text[..offset]
        .chars()
        .next_back()
        .is_some_and(char::is_alphabetic)
        && text[offset + ch.len_utf8()..]
            .chars()
            .next()
            .is_some_and(char::is_alphabetic)
}

fn protected_token(text: &str, start: usize) -> bool {
    let end = text[start..]
        .find(char::is_whitespace)
        .map_or(text.len(), |i| start + i);
    let token = &text[start..end];
    let folded = token.to_lowercase();
    let mut chars = token.chars().peekable();
    let mut previous = None;
    let mut dotted_identifier = false;
    while let Some(value) = chars.next() {
        if value == '.'
            && previous.is_some_and(char::is_alphanumeric)
            && chars.peek().is_some_and(|next| next.is_alphanumeric())
        {
            dotted_identifier = true;
            break;
        }
        previous = Some(value);
    }
    token.contains("://")
        || token.contains('@')
        || token.contains('`')
        || folded.starts_with("www.")
        || dotted_identifier
}
