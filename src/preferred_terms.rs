//! Conservative, local spelling cleanup for a preferred-word list.
//!
//! This is not Parakeet decoder bias: only a unique, very close spelling of
//! a listed single word is changed after recognition. Compound names restore
//! only their exact spelling and case, with identical punctuation. Exact correction pairs
//! remain available for larger or ambiguous recognition errors.

use crate::transcript_guard::{match_folded, right_boundary, ProtectedText};
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
        } else if exact_name(term) {
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
    let guard = ProtectedText::new(text, protected);
    while offset < text.len() {
        let ch = text[offset..].chars().next().unwrap();
        if !ch.is_alphabetic() {
            output.push(ch);
            offset += ch.len_utf8();
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
        let joined_to_other_token = text[..start]
            .chars()
            .next_back()
            .is_some_and(|value| value.is_alphanumeric() || value == '_')
            || text[offset..]
                .chars()
                .next()
                .is_some_and(|value| value.is_alphanumeric() || value == '_');
        if !guard.allows(start, offset) || joined_to_other_token {
            output.push_str(candidate);
            continue;
        }
        if let Some((phrase_end, written)) = match_phrase(text, start, &phrases, &guard) {
            output.push_str(written);
            if output.len() > text.len().saturating_add(MAX_EXPANSION_BYTES) {
                return text.to_string();
            }
            offset = phrase_end;
            continue;
        }
        if compound_component(text, start, offset) {
            output.push_str(candidate);
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
    guard: &ProtectedText,
) -> Option<(usize, &'a str)> {
    for phrase in phrases {
        let Some(end) = match_folded(text, start, &phrase.folded) else {
            continue;
        };
        if right_boundary(text, end)
            && guard.allows(start, end)
            && !compound_component(text, start, end)
        {
            return Some((end, phrase.written.as_str()));
        }
    }
    None
}

fn exact_name(term: &str) -> bool {
    let chars: Vec<char> = term.chars().collect();
    chars.iter().enumerate().all(|(index, ch)| {
        ch.is_alphabetic()
            || (matches!(ch, ' ' | '-' | '\'' | '‘' | '’')
                && index > 0
                && index + 1 < chars.len()
                && chars[index - 1].is_alphabetic()
                && chars[index + 1].is_alphabetic())
    })
}

fn compound_component(text: &str, start: usize, end: usize) -> bool {
    let before = text[..start].chars().next_back();
    if before == Some('-') || text[end..].starts_with('-') {
        return true;
    }
    // A possessive suffix may follow a corrected name, but neither half of
    // an unknown apostrophe-joined name is a fuzzy spelling candidate.
    if matches!(before, Some('\'' | '‘' | '’')) {
        let prefix = &text[..start - before.unwrap().len_utf8()];
        if prefix.chars().next_back().is_some_and(char::is_alphabetic) {
            return true;
        }
    }
    if let Some(quote @ ('\'' | '‘' | '’')) = text[end..].chars().next() {
        let tail = &text[end + quote.len_utf8()..];
        if tail.chars().next().is_some_and(char::is_alphabetic)
            && !(tail.starts_with('s') && right_boundary(tail, 1))
        {
            return true;
        }
    }
    false
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
