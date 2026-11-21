//! Conservative, local spelling cleanup for a preferred-word list.
//!
//! This is not Parakeet decoder bias: only a unique, very close spelling of
//! a listed single word is changed after recognition. Compound names restore
//! only their exact spelling and case, with identical punctuation. Exact correction pairs
//! remain available for larger or ambiguous recognition errors. For English
//! output, `sound_alike` may also propose short spoken windows that sound like
//! a listed name; exact matches always win over those proposals.

use crate::transcript_guard::{left_boundary, match_folded, right_boundary, ProtectedText};
use sound_alike::Candidate;
use std::collections::HashSet;
use std::ops::Range;

#[path = "sound_alike.rs"]
pub(crate) mod sound_alike;

const MAX_EXPANSION_BYTES: usize = 4096;
/// How many of a request's words, in the caller's order, may also replace a close
/// spelling. It is the size the user's own list is limited to, the size these guesses
/// were designed and measured for. Later words of a request only restore their exact
/// spelling: with a thousand ordinary words listed, a correctly recognized word is
/// too often one edit away from some other listed word.
pub(crate) const REQUEST_GUESSED_TERMS: usize = 128;

#[derive(Clone)]
struct Word<'a> {
    written: &'a str,
    folded: Vec<char>,
    /// A close spelling of this word may be replaced, not only its exact form.
    guessed: bool,
    /// A request word written without capitals: it never changes the capitalization
    /// the recognizer chose (a sentence start stays capitalized).
    keeps_case: bool,
}

/// What a list may do. The user's own list restores its written form everywhere.
#[derive(Clone, Copy)]
struct Policy {
    /// Only this many leading terms may replace anything but their exact form.
    guessed_terms: usize,
    /// Terms written without capitals leave capitalization alone.
    lowercase_keeps_case: bool,
}

const OWN_LIST: Policy = Policy { guessed_terms: usize::MAX, lowercase_keeps_case: false };
const REQUEST: Policy = Policy { guessed_terms: REQUEST_GUESSED_TERMS, lowercase_keeps_case: true };

struct Phrase<'a> {
    written: &'a str,
    folded: String,
    char_count: usize,
    is_alias: bool,
}

#[cfg(test)]
pub(crate) fn apply(text: &str, terms: &[String]) -> String {
    apply_with_protected(text, terms, &[])
}

#[cfg(test)]
pub(crate) fn apply_with_protected(
    text: &str,
    terms: &[String],
    protected: &[Range<usize>],
) -> String {
    apply_with_ranges(text, terms, protected).0
}

/// Return protection in OUTPUT coordinates for the final filler pass.
pub(crate) fn apply_with_ranges(
    text: &str,
    terms: &[String],
    protected: &[Range<usize>],
) -> (String, Vec<Range<usize>>) {
    apply_inner(text, terms, protected, None, OWN_LIST)
}

/// Words another app sent for this one request. Applied after the user's own list,
/// whose matches arrive in `protected`, so a request can add spellings but never
/// change or weaken what the user's list did. The first `REQUEST_GUESSED_TERMS`
/// words are matched like the user's own words and names (exact form, close
/// spelling, known spoken forms); the rest restore only their exact spelling and
/// case. No request word is matched by sound: that guess joins ordinary words into a
/// listed name and is kept for the list the user wrote. A keyboard's dictionary is
/// mostly ordinary lowercase words, so a word sent without capitals never lowers a
/// capital the recognizer wrote, for example at the start of a sentence.
pub(crate) fn apply_request(
    text: &str,
    terms: &[String],
    protected: &[Range<usize>],
) -> (String, Vec<Range<usize>>) {
    apply_inner(text, terms, protected, None, REQUEST)
}

/// As `apply_with_ranges`, also proposing English sound-alike windows that
/// `gate` allows. No window spans one of `joins` (ascending byte offsets in
/// `text`).
pub(crate) fn apply_with_sound_alike(
    text: &str,
    terms: &[String],
    protected: &[Range<usize>],
    gate: sound_alike::Gate,
    joins: &[usize],
) -> (String, Vec<Range<usize>>) {
    apply_inner(text, terms, protected, Some((gate, joins)), OWN_LIST)
}

fn apply_inner<'a>(
    text: &str,
    terms: &'a [String],
    protected: &[Range<usize>],
    sound: Option<(sound_alike::Gate, &[usize])>,
    policy: Policy,
) -> (String, Vec<Range<usize>>) {
    if text.is_empty() || terms.is_empty() {
        return (text.to_string(), protected.to_vec());
    }
    let mut words = Vec::new();
    let mut phrases = Vec::new();
    // Listed words and names a sound-alike window may become.
    let mut eligible = Vec::new();
    let mut seen = HashSet::new();
    for (index, term) in terms.iter().enumerate() {
        let folded = term.to_lowercase();
        if !seen.insert(folded.clone()) {
            continue;
        }
        let guessed = index < policy.guessed_terms;
        let keeps_case = policy.lowercase_keeps_case && !term.chars().any(char::is_uppercase);
        if keeps_case && (!guessed || !term.chars().all(char::is_alphabetic)) {
            // Its exact form is already written as the caller wants it.
            continue;
        }
        if term.chars().all(char::is_alphabetic) {
            words.push(Word {
                written: term,
                folded: folded.chars().collect(),
                guessed,
                keeps_case,
            });
            eligible.push(term.as_str());
            if folded == "npub" && guessed {
                // Decoder output may split the spoken prefix into two words.
                // Keep these forms tied to the user's explicit npub entry.
                for heard in ["en pub", "en pop", "n pub", "n pop", "m pups"] {
                    phrases.push(Phrase {
                        written: term,
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
                written: term,
                char_count: term.chars().count(),
                folded,
                is_alias: false,
            });
            eligible.push(term.as_str());
        }
    }
    if words.is_empty() && phrases.is_empty() {
        return (text.to_string(), protected.to_vec());
    }
    // Longer exact phrases win; a user's exact phrase always beats a known
    // spoken alias of the same length.
    phrases.sort_by_key(|phrase| (std::cmp::Reverse(phrase.char_count), phrase.is_alias));

    // Exact matches are hard: nothing may overlap them. Close spellings and
    // sound-alike windows are fuzzy proposals resolved afterwards.
    let mut hard = Vec::new();
    let mut fuzzy = Vec::new();
    // Reuse the scalar buffer across candidates. Keep String's contextual
    // lowercase rules (for example Greek final sigma) when filling it.
    let mut folded = Vec::new();
    let mut offset = 0;
    let guard = ProtectedText::new(text, protected);
    while offset < text.len() {
        let ch = text[offset..].chars().next().unwrap();
        // Exact names may contain digits (or start with one). Match the whole
        // name before the alphabetic fuzzy-word scan; digits are never guessed.
        if ch.is_alphanumeric() && left_boundary(text, offset) {
            if let Some((end, written)) = match_phrase(text, offset, &phrases, &guard) {
                hard.push(Candidate { range: offset..end, written });
                offset = end;
                continue;
            }
        }
        if !ch.is_alphabetic() {
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
        let joined_to_other_token = text[..start]
            .chars()
            .next_back()
            .is_some_and(|value| value.is_alphanumeric() || value == '_')
            || text[offset..]
                .chars()
                .next()
                .is_some_and(|value| value.is_alphanumeric() || value == '_');
        if !guard.allows(start, offset) || joined_to_other_token {
            continue;
        }
        if compound_component(text, start, offset) {
            continue;
        }
        folded.clear();
        folded.extend(text[start..offset].to_lowercase().chars());
        let first_fuzzy = fuzzy.len();
        for word in &words {
            if word.folded == folded {
                fuzzy.truncate(first_fuzzy);
                if !word.keeps_case {
                    hard.push(Candidate { range: start..offset, written: word.written });
                }
                break;
            }
            if !word.guessed
                || (word.keeps_case && text[start..].chars().next().is_some_and(char::is_uppercase))
            {
                continue;
            }
            // A few spoken identifier forms are too different from their
            // spelling for edit distance to recover. Keep these aliases
            // explicit and conditional on the user listing that identifier;
            // a general phonetic rewrite would change ordinary words.
            if known_spoken_alias(&folded, &word.folded) {
                fuzzy.push(Candidate { range: start..offset, written: word.written });
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
            fuzzy.push(Candidate { range: start..offset, written: word.written });
        }
        // A guessed spelling must be a whole word: letters joined to a
        // combining mark, symbol or another script are left alone.
        if fuzzy.len() > first_fuzzy
            && !sound_alike::is_stem(text, start, offset)
        {
            fuzzy.truncate(first_fuzzy);
        }
    }
    if let Some((gate, joins)) = sound.filter(|(gate, _)| gate.is_open()) {
        let terms = sound_alike::Terms::new(&eligible);
        if !terms.is_empty() {
            let baseline = fuzzy.len();
            sound_alike::candidates(
                text,
                &sound_alike::tokens(text),
                &terms,
                gate,
                joins,
                |start, end| guard.allows(start, end),
                &mut fuzzy,
            );
            // A one-edit or alias match wins over any sound-alike guess
            // for the same words.
            if baseline > 0 {
                let mut spelled: Vec<Range<usize>> = fuzzy[..baseline].iter().map(|c| c.range.clone()).collect();
                spelled.sort_unstable_by_key(|range| range.start);
                spelled.dedup_by(|next, kept| {
                    let overlaps = next.start < kept.end;
                    if overlaps {
                        kept.end = kept.end.max(next.end);
                    }
                    overlaps
                });
                let mut kept = baseline;
                for index in baseline..fuzzy.len() {
                    let guess = &fuzzy[index].range;
                    let after = spelled.partition_point(|range| range.end <= guess.start);
                    if spelled.get(after).is_none_or(|range| guess.end <= range.start) {
                        fuzzy.swap(kept, index);
                        kept += 1;
                    }
                }
                fuzzy.truncate(kept);
            }
        }
    }
    resolve(&mut hard, fuzzy);

    // Bound the whole output, not each edit, so a later shrinking edit can
    // offset an earlier growing one.
    let removed: usize = hard.iter().map(|candidate| candidate.range.len()).sum();
    let Some(output_len) = hard
        .iter()
        .try_fold(text.len() - removed, |len, candidate| len.checked_add(candidate.written.len()))
        .filter(|&len| len <= text.len().saturating_add(MAX_EXPANSION_BYTES))
    else {
        return (text.to_string(), protected.to_vec());
    };
    let mut output = String::with_capacity(output_len);
    let mut matched_ranges = Vec::with_capacity(hard.len() + protected.len());
    let mut edits = Vec::with_capacity(hard.len());
    let mut copied = 0;
    for candidate in &hard {
        output.push_str(&text[copied..candidate.range.start]);
        let output_start = output.len();
        output.push_str(candidate.written);
        matched_ranges.push(output_start..output.len());
        edits.push((candidate.range.end, output.len()));
        copied = candidate.range.end;
    }
    output.push_str(&text[copied..]);
    // Explicit replacements cannot overlap edits because the guard rejected
    // those matches. Remap their offsets using the last preceding edit.
    let mut inherited = protected.to_vec();
    inherited.sort_by_key(|range| range.start);
    let mut edit_index = 0;
    let mut delta = 0isize;
    for range in inherited {
        while let Some(&(input_end, output_end)) = edits.get(edit_index) {
            if input_end > range.start {
                break;
            }
            delta = output_end as isize - input_end as isize;
            edit_index += 1;
        }
        matched_ranges
            .push((range.start as isize + delta) as usize..(range.end as isize + delta) as usize);
    }
    matched_ranges.sort_by_key(|range| range.start);
    (output, matched_ranges)
}

/// Add each fuzzy cluster that one written form covers whole to `hard`.
/// Fuzzy proposals overlapping an exact match are dropped. Overlapping
/// proposals form a cluster; it is applied only if every proposal spanning
/// the entire cluster names the same listed word, otherwise nothing changes.
fn resolve<'a>(hard: &mut Vec<Candidate<'a>>, mut fuzzy: Vec<Candidate<'a>>) {
    fuzzy.retain(|candidate| {
        let index = hard.partition_point(|exact| exact.range.end <= candidate.range.start);
        hard.get(index).map_or(true, |exact| exact.range.start >= candidate.range.end)
    });
    fuzzy.sort_by_key(|candidate| (candidate.range.start, std::cmp::Reverse(candidate.range.end)));
    let mut first = 0;
    while first < fuzzy.len() {
        let span_start = fuzzy[first].range.start;
        let mut span_end = fuzzy[first].range.end;
        let mut next = first + 1;
        while next < fuzzy.len() && fuzzy[next].range.start < span_end {
            span_end = span_end.max(fuzzy[next].range.end);
            next += 1;
        }
        let mut covering = fuzzy[first..next]
            .iter()
            .filter(|candidate| candidate.range == (span_start..span_end));
        if let Some(chosen) = covering.next() {
            if covering.all(|other| other.written == chosen.written) {
                hard.push(chosen.clone());
            }
        }
        first = next;
    }
    hard.sort_by_key(|candidate| candidate.range.start);
}

fn match_phrase<'a>(
    text: &str,
    start: usize,
    phrases: &[Phrase<'a>],
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
            return Some((end, phrase.written));
        }
    }
    None
}

fn exact_name(term: &str) -> bool {
    let chars: Vec<char> = term.chars().collect();
    chars.iter().any(|ch| ch.is_alphabetic())
        && chars.iter().enumerate().all(|(index, ch)| {
            ch.is_alphanumeric()
                || (matches!(ch, ' ' | '-' | '\'' | '‘' | '’')
                    && index > 0
                    && index + 1 < chars.len()
                    && chars[index - 1].is_alphanumeric()
                    && chars[index + 1].is_alphanumeric())
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
