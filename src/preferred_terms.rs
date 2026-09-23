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
    let mut seen = HashSet::new();
    for term in terms {
        if !term.chars().all(char::is_alphabetic) {
            continue; // Phrases and punctuation need explicit correction pairs.
        }
        let folded = term.to_lowercase();
        if seen.insert(folded.clone()) {
            words.push(Word {
                written: term.clone(),
                folded: folded.chars().collect(),
            });
        }
    }
    if words.is_empty() {
        return text.to_string();
    }

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
        let folded: Vec<char> = candidate.to_lowercase().chars().collect();
        let mut exact = None;
        let mut nearby = None;
        let mut ambiguous = false;
        for word in &words {
            if word.folded == folded {
                exact = Some(word.written.as_str());
                break;
            }
            // Three common leading letters and the same ending make a one-
            // edit typo a useful guess without treating short ordinary words
            // as interchangeable. Never stem or change grammatical suffixes.
            if word.folded.len() < 6
                || folded.len() < 6
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
