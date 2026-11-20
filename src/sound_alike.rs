//! Bounded English sound-alike candidates for listed preferred words.
//!
//! A window of one to three adjacent plain words is compared with each
//! eligible listed word by a small English sound key. Single-word windows
//! must not be ordinary dictionary words or names; multiword windows must
//! stay within a spelling budget of the listed word and may neither end in a
//! function word nor be half or more function words. Candidates are only
//! proposals: the caller resolves overlaps and never lets them replace an
//! exact or one-edit match.

use std::collections::{HashMap, HashSet};
use std::ops::Range;

const ALPHABET: &str = "abcdefghijklmnopqrstuvwxyz";
const KEY_ALPHABET: &[u8; 26] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const MAX_WINDOW_WORDS: usize = 3;
const MAX_BUDGET: usize = 3;

/// Which windows may be rewritten for the transcript's language evidence.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Gate {
    pub(crate) single: bool,
    pub(crate) multi: bool,
    /// Join only words that are themselves ordinary English words.
    pub(crate) multi_common_only: bool,
}

impl Gate {
    pub(crate) fn is_open(self) -> bool {
        self.single || self.multi
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Candidate<'a> {
    pub(crate) range: Range<usize>,
    pub(crate) written: &'a str,
}

/// One whitespace- or punctuation-delimited word whose letters all fold to
/// a-z. `stem` excludes a possessive or trailing apostrophe.
pub(crate) struct Token {
    pub(crate) stem: Range<usize>,
    /// The folded letters, in `Tokens::folded`.
    folded: Range<usize>,
    possessive: bool,
    /// No capital letter after the first one (rules out acronyms and CamelCase).
    plain: bool,
}

pub(crate) struct Tokens {
    list: Vec<Token>,
    folded: String,
}

impl Tokens {
    fn folded(&self, token: &Token) -> &str {
        &self.folded[token.folded.clone()]
    }
}

/// `is_separator` for each ASCII byte, as a bit set.
const ASCII_SEPARATORS: u128 = {
    let mut set = 0;
    let mut byte = 0;
    while byte < 128 {
        if is_separator(byte as u8 as char) {
            set |= 1 << byte;
        }
        byte += 1;
    }
    set
};

const fn is_separator(ch: char) -> bool {
    ch.is_whitespace()
        || (ch.is_ascii_punctuation() && !matches!(ch, '\'' | '_' | '-'))
        || matches!(
            ch,
            '“' | '”' | '„' | '«' | '»' | '‹' | '›' | '…' | '–' | '—' | '¿' | '¡'
        )
}

/// Whole tokens only: a word joined to a digit, symbol, hyphen or an
/// unfoldable letter or mark is not a token at all.
pub(crate) fn tokens(text: &str) -> Tokens {
    let mut tokens = Tokens {
        list: Vec::with_capacity(text.len() / 4),
        folded: String::with_capacity(text.len()),
    };
    let bytes = text.as_bytes();
    let mut start = None;
    let mut offset = 0;
    while offset <= bytes.len() {
        // ASCII is checked byte by byte; anything else is decoded.
        let (separator, width) = match bytes.get(offset) {
            None => (true, 1),
            Some(&byte) if byte.is_ascii() => (ASCII_SEPARATORS >> byte & 1 != 0, 1),
            Some(_) => {
                let ch = text[offset..].chars().next().expect("offset is a char boundary");
                (is_separator(ch), ch.len_utf8())
            }
        };
        if separator {
            if let Some(start) = start.take() {
                if let Some(token) = token(text, start, offset, &mut tokens.folded) {
                    tokens.list.push(token);
                }
            }
        } else if start.is_none() {
            start = Some(offset);
        }
        offset += width;
    }
    tokens
}

/// Fold `text[start..end]` onto `folded`, which is left unchanged when the
/// word is not a token.
fn token(text: &str, start: usize, end: usize, folded: &mut String) -> Option<Token> {
    let folded_start = folded.len();
    let token = fold_token(text, start, end, folded);
    if token.is_none() {
        folded.truncate(folded_start);
    }
    token
}

fn fold_token(text: &str, start: usize, end: usize, folded: &mut String) -> Option<Token> {
    let folded_start = folded.len();
    let word = &text[start..end];
    if word.bytes().all(|byte| byte.is_ascii_alphabetic()) {
        let plain = !word.bytes().skip(1).any(|byte| byte.is_ascii_uppercase());
        folded.push_str(word);
        folded[folded_start..].make_ascii_lowercase();
        return Some(Token { stem: start..end, folded: folded_start..folded.len(), possessive: false, plain });
    }
    let mut plain = true;
    for (index, (offset, ch)) in text[start..end].char_indices().enumerate() {
        if matches!(ch, '\'' | '\u{2019}' | '\u{2018}' | '\u{02BC}') {
            if index == 0 {
                return None;
            }
            let possessive = match &text[start + offset + ch.len_utf8()..end] {
                "s" | "S" => true,
                "" => false,
                _ => return None,
            };
            return Some(Token { stem: start..start + offset, folded: folded_start..folded.len(), possessive, plain });
        }
        plain &= index == 0 || !ch.is_uppercase();
        if ch.is_ascii_alphabetic() {
            folded.push(ch.to_ascii_lowercase());
        } else {
            folded.push_str(fold_char(ch)?);
        }
    }
    Some(Token { stem: start..end, folded: folded_start..folded.len(), possessive: false, plain })
}

/// Whether `start..end` is the whole stem of the token around it.
pub(crate) fn is_stem(text: &str, start: usize, end: usize) -> bool {
    let token_start = text[..start]
        .char_indices()
        .rev()
        .find(|&(_, ch)| is_separator(ch))
        .map_or(0, |(offset, ch)| offset + ch.len_utf8());
    let token_end = text[start..]
        .char_indices()
        .find(|&(_, ch)| is_separator(ch))
        .map_or(text.len(), |(offset, _)| start + offset);
    token(text, token_start, token_end, &mut String::new()).is_some_and(|token| token.stem == (start..end))
}

fn usable(token: &Token, folded: &str) -> bool {
    folded.len() >= 2 && !matches!(folded, "uh" | "um") && token.plain
}

fn adjacent(previous: &Token, next: &Token, text: &[u8]) -> bool {
    !previous.possessive
        && next.stem.start == previous.stem.end + 1
        && text[previous.stem.end] == b' '
}

/// Lowercase Latin letters with common diacritics folded to a-z.
pub(crate) fn fold_char(ch: char) -> Option<&'static str> {
    let mut lower = ch.to_lowercase();
    let ch = lower.next()?;
    if lower.next().is_some() {
        return None;
    }
    let letter = match ch {
        'a'..='z' => ch,
        'à'..='å' => 'a',
        'ç' => 'c',
        'è'..='ë' => 'e',
        'ì'..='ï' => 'i',
        'ñ' => 'n',
        'ò'..='ö' | 'ø' => 'o',
        'ù'..='ü' => 'u',
        'ý' | 'ÿ' => 'y',
        'ß' => return Some("ss"),
        'æ' => return Some("ae"),
        'œ' => return Some("oe"),
        _ => return None,
    };
    let index = (letter as u8 - b'a') as usize;
    Some(&ALPHABET[index..index + 1])
}

pub(crate) fn fold(word: &str) -> Option<String> {
    word.chars().map(fold_char).collect()
}

fn is_vowel(letter: u8) -> bool {
    matches!(letter, b'a' | b'e' | b'i' | b'o' | b'u')
}

/// A small English sound key over folded letters. Every vowel sound is `V`;
/// silent and doubled letters and common digraphs collapse.
pub(crate) fn key(folded: &str) -> String {
    let mut key = Vec::new();
    key_into(folded.as_bytes(), &mut Vec::new(), &mut key);
    String::from_utf8(key).expect("keys are ASCII")
}

/// `key` into reusable buffers.
fn key_into(folded: &[u8], letters: &mut Vec<u8>, key: &mut Vec<u8>) {
    letters.clear();
    key.clear();
    push_deduped(folded, letters);
    prepare(letters);
    let mut index = 0;
    while index < letters.len() {
        let (symbol, consumed) = key_step(letters, index);
        for &symbol in symbol {
            if key.last() != Some(&symbol) {
                key.push(symbol);
            }
        }
        index += consumed;
    }
}

/// Appends letters, collapsing each run of one letter.
fn push_deduped(folded: &[u8], letters: &mut Vec<u8>) {
    for &letter in folded {
        if letters.last() != Some(&letter) {
            letters.push(letter);
        }
    }
}

/// Drops a silent final e and applies the word-start rules.
fn prepare(letters: &mut Vec<u8>) {
    if letters.len() > 2 && letters.last() == Some(&b'e') {
        letters.pop();
    }
    apply_start_rules(letters);
}

fn apply_start_rules(letters: &mut Vec<u8>) {
    match letters.as_slice() {
        [b'k' | b'g' | b'p', b'n', ..] | [b'w', b'r', ..] | [b'p', b's', ..] => {
            letters.remove(0);
        }
        [b'x', ..] => letters[0] = b's',
        [b'w', b'h', ..] => {
            letters.remove(1);
        }
        _ => {}
    }
}

/// The `lead_slot` of the key of every window that begins with this folded
/// word, when the word settles it. A window's run-collapsed letters begin
/// with the word's own, and only the last of them can be dropped as a silent
/// e, so the others are settled. The start rules read two letters and each
/// key step at most two letters ahead.
fn first_lead(folded: &str, letters: &mut Vec<u8>) -> Option<usize> {
    letters.clear();
    push_deduped(folded.as_bytes(), letters);
    letters.pop();
    if letters.len() < 2 {
        return None;
    }
    apply_start_rules(letters);
    let mut index = 0;
    while index + 2 < letters.len() {
        let (symbol, consumed) = key_step(letters, index);
        if !symbol.is_empty() {
            return Some(lead_slot(symbol.first()));
        }
        index += consumed;
    }
    None
}

/// The key symbols for `letters[index..]`'s next sound and the letters it
/// consumes. Reads only `letters[index - 1..=index + 2]`.
fn key_step(letters: &[u8], index: usize) -> (&'static [u8], usize) {
    let at = |index: usize| letters.get(index).copied();
    let next = at(index + 1);
    let front_vowel = |letter: Option<u8>| matches!(letter, Some(b'e' | b'i' | b'y'));
    match letters[index] {
        b'a' | b'e' | b'i' | b'o' | b'u' => (b"V", 1),
        b'y' if index == 0 && next.is_some_and(is_vowel) => (b"Y", 1),
        b'y' => (b"V", 1),
        b't' if next == Some(b'c') && at(index + 2) == Some(b'h') => (b"", 1),
        b't' if next == Some(b'h') => (b"0", 2),
        b'c' if next == Some(b'h') && at(index + 2) == Some(b'r') => (b"K", 2),
        b'c' if next == Some(b'h') => (b"X", 2),
        b'c' if next == Some(b'k') => (b"K", 2),
        b'c' if front_vowel(next) => (b"S", 1),
        b'q' if next == Some(b'u') => (b"K", 2),
        b'c' | b'q' => (b"K", 1),
        b'x' => (b"KS", 1),
        b'z' => (b"S", 1),
        b'p' if next == Some(b'h') => (b"F", 2),
        b's' if next == Some(b'c') && at(index + 2) == Some(b'h') => (b"X", 3),
        b's' if next == Some(b'h') => (b"X", 2),
        b'd' if next == Some(b'g') && front_vowel(at(index + 2)) => (b"J", 2),
        b'h' if next.is_some_and(is_vowel)
            && (index == 0 || is_vowel(letters[index - 1])) =>
        {
            (b"H", 1)
        }
        b'h' => (b"", 1),
        b'w' if next.is_some_and(is_vowel) => (b"W", 1),
        b'w' => (b"", 1),
        letter => {
            let position = (letter - b'a') as usize;
            (&KEY_ALPHABET[position..position + 1], 1)
        }
    }
}

/// Equal keys, or keys that differ by one vowel symbol inserted after the
/// first sound (an unstressed vowel recognizers often add or drop).
pub(crate) fn keys_match(a: &[u8], b: &[u8]) -> bool {
    if a == b {
        return true;
    }
    let (long, short) = if a.len() > b.len() { (a, b) } else { (b, a) };
    if long.len() != short.len() + 1 {
        return false;
    }
    let index = long.iter().zip(short).position(|(x, y)| x != y).unwrap_or(short.len());
    index >= 1 && long[index] == b'V' && long[index + 1..] == short[index..]
}

/// Optimal string alignment distance (adjacent transpositions count once).
#[cfg(test)]
pub(crate) fn osa(a: &[u8], b: &[u8]) -> usize {
    osa_with(a, b, &mut Default::default())
}

/// `osa` with reusable rows.
fn osa_with(a: &[u8], b: &[u8], rows: &mut [Vec<usize>; 3]) -> usize {
    let [before, previous, current] = rows;
    for row in [&mut *before, &mut *current] {
        row.clear();
        row.resize(b.len() + 1, 0);
    }
    previous.clear();
    previous.extend(0..=b.len());
    for i in 1..=a.len() {
        current[0] = i;
        for j in 1..=b.len() {
            let cost = usize::from(a[i - 1] != b[j - 1]);
            current[j] = (previous[j] + 1).min(current[j - 1] + 1).min(previous[j - 1] + cost);
            if i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1] {
                current[j] = current[j].min(before[j - 2] + 1);
            }
        }
        std::mem::swap(before, previous);
        std::mem::swap(previous, current);
    }
    previous[b.len()]
}

/// Spelling edits a joined multiword window may differ by.
pub(crate) fn budget(length: usize) -> usize {
    match length {
        0..=6 => 1,
        7..=10 => 2,
        _ => MAX_BUDGET,
    }
}

const COMMON_WORDS: &str = include_str!("sound_alike_words_en.txt");

/// Byte offset of the first word, after the provenance header line.
const COMMON_WORDS_START: usize = {
    let bytes = COMMON_WORDS.as_bytes();
    let mut index = 0;
    while index < bytes.len() && bytes[index] != b'\n' {
        index += 1;
    }
    if index < bytes.len() { index + 1 } else { bytes.len() }
};

/// Sorted articles, pronouns, auxiliaries, conjunctions, short prepositions
/// and question words. "no" is left out so "no stir" can still be heard.
pub(crate) const FUNCTION_WORDS: &[&str] = &[
    "a", "am", "an", "and", "are", "as", "at", "be", "been", "being", "but", "by", "can", "could", "did", "do", "does",
    "for", "from", "had", "has", "have", "he", "her", "hers", "him", "his", "how", "i", "if", "in", "into", "is", "it",
    "its", "may", "me", "might", "mine", "must", "my", "nor", "of", "on", "onto", "or", "our", "ours", "shall", "she",
    "should", "so", "than", "that", "the", "thee", "their", "theirs", "them", "then", "these", "they", "this", "those",
    "thou", "thy", "to", "upon", "us", "was", "we", "were", "what", "when", "where", "which", "who", "whom", "whose",
    "why", "will", "with", "would", "ye", "yet", "you", "your", "yours",
];

pub(crate) fn is_function_word(word: &str) -> bool {
    // No function word is longer than six letters.
    word.len() <= 6 && FUNCTION_WORDS.binary_search(&word).is_ok()
}

/// Binary search of the sorted, folded common-word list (after its header).
pub(crate) fn is_common_word(word: &str) -> bool {
    let body = &COMMON_WORDS.as_bytes()[COMMON_WORDS_START..];
    let (mut low, mut high) = (0, body.len());
    while low < high {
        let middle = low + (high - low) / 2;
        let start = body[..middle].iter().rposition(|&b| b == b'\n').map_or(0, |index| index + 1);
        let end = body[start..].iter().position(|&b| b == b'\n').map_or(body.len(), |index| start + index);
        match body[start..end].cmp(word.as_bytes()) {
            std::cmp::Ordering::Equal => return true,
            std::cmp::Ordering::Less => low = end + 1,
            std::cmp::Ordering::Greater => high = start,
        }
    }
    false
}

struct Term<'a> {
    written: &'a str,
    folded: String,
    key: Vec<u8>,
}

/// Non-vowel symbols in a key.
fn key_sounds(key: &[u8]) -> usize {
    key.iter().filter(|&&symbol| symbol != b'V').count()
}

/// At least `key_sounds(key(folded))`: each key step consumes a letter, and
/// only a non-vowel letter yields a non-vowel symbol (two for `x`).
fn sounds_bound(folded: &str) -> usize {
    folded.bytes().map(|letter| if is_vowel(letter) { 0 } else { 1 + usize::from(letter == b'x') }).sum()
}

/// Eligible listed words and phrases.
pub(crate) struct Terms<'a> {
    terms: Vec<Term<'a>>,
    single: Vec<usize>,
    /// The fewest non-vowel key symbols of any single listed word.
    fewest_single_sounds: usize,
    /// Terms by the first symbol of their key (`lead_slot`), shortest
    /// first, with their folded length and the letters each contains.
    by_lead: Vec<Vec<(usize, u32, usize)>>,
    /// The longest joined window any term can match.
    longest_window: usize,
}

/// Which group of `Terms::by_lead` a key's first symbol selects: one per
/// letter symbol, one for `0`, and one for an empty key.
fn lead_slot(lead: Option<&u8>) -> usize {
    match lead {
        Some(&symbol @ b'A'..=b'Z') => usize::from(symbol - b'A'),
        Some(_) => 26,
        None => 27,
    }
}

/// The letters a folded (a-z) word contains.
fn present(folded: &[u8]) -> u32 {
    folded.iter().fold(0, |present, letter| present | 1 << (letter - b'a'))
}

impl<'a> Terms<'a> {
    pub(crate) fn new(written: &[&'a str]) -> Self {
        let mut seen = HashSet::new();
        let mut terms = Vec::new();
        let mut single = Vec::new();
        let mut fewest_single_sounds = usize::MAX;
        let mut by_lead = vec![Vec::new(); 28];
        let mut longest_window = 0;
        for &term in written {
            let Some((folded, words)) = fold_term(term) else {
                continue;
            };
            if !seen.insert(folded.clone()) {
                continue;
            }
            let key = key(&folded).into_bytes();
            // Short names and names with few consonant sounds collide with
            // too many ordinary words; acronyms are spelled, not spoken.
            let sounds = key_sounds(&key);
            let distinct = words > 1 || sounds >= 3;
            if folded.len() < 5 || all_caps(term) || !distinct {
                continue;
            }
            let index = terms.len();
            if words == 1 {
                single.push(index);
                fewest_single_sounds = fewest_single_sounds.min(sounds);
            }
            by_lead[lead_slot(key.first())].push((folded.len(), present(folded.as_bytes()), index));
            longest_window = longest_window.max(folded.len() + budget(folded.len()));
            terms.push(Term { written: term, folded, key });
        }
        for group in &mut by_lead {
            group.sort_by_key(|&(length, ..)| length);
        }
        Self { terms, single, fewest_single_sounds, by_lead, longest_window }
    }

    pub(crate) fn is_empty(&self) -> bool {
        self.terms.is_empty()
    }
}

fn fold_term(term: &str) -> Option<(String, usize)> {
    let mut folded = String::new();
    let mut words = 0;
    for part in term.split(' ') {
        if part.is_empty() {
            return None;
        }
        folded.push_str(&fold(part)?);
        words += 1;
    }
    Some((folded, words))
}

fn all_caps(term: &str) -> bool {
    let letters: Vec<char> = term.chars().filter(|ch| ch.is_alphabetic()).collect();
    letters.len() >= 2 && letters.iter().all(|ch| ch.is_uppercase())
}

/// Push sound-alike candidates for every allowed window. A window never
/// spans a stitch join (byte offsets where two independent recognition
/// results were concatenated without alignment).
pub(crate) fn candidates<'a>(
    text: &str,
    tokens: &Tokens,
    terms: &Terms<'a>,
    gate: Gate,
    joins: &[usize],
    allows: impl Fn(usize, usize) -> bool,
    out: &mut Vec<Candidate<'a>>,
) {
    scan(text, tokens, terms, gate, joins, allows, true, out);
}

/// The same scan, comparing every multiword window with every term.
#[cfg(test)]
pub(crate) fn candidates_unbucketed<'a>(
    text: &str,
    tokens: &Tokens,
    terms: &Terms<'a>,
    gate: Gate,
    joins: &[usize],
    allows: impl Fn(usize, usize) -> bool,
    out: &mut Vec<Candidate<'a>>,
) {
    scan(text, tokens, terms, gate, joins, allows, false, out);
}

#[allow(clippy::too_many_arguments)]
fn scan<'a>(
    text: &str,
    tokens: &Tokens,
    terms: &Terms<'a>,
    gate: Gate,
    joins: &[usize],
    allows: impl Fn(usize, usize) -> bool,
    bucketed: bool,
    out: &mut Vec<Candidate<'a>>,
) {
    let bytes = text.as_bytes();
    // Whether each word is a function word and whether it is common, looked
    // up only once a window matched.
    let mut function: Vec<Option<bool>> = if gate.multi { vec![None; tokens.list.len()] } else { Vec::new() };
    let mut common: Vec<Option<bool>> = if gate.multi && gate.multi_common_only { vec![None; tokens.list.len()] } else { Vec::new() };
    // Single-word matches depend only on the folded word, which repeats.
    let mut heard: HashMap<&str, Range<usize>> = HashMap::new();
    let mut single_matches = Vec::new();
    let mut scratch = Scratch::default();
    let list = &tokens.list;
    for first in 0..list.len() {
        let mut lead = None;
        let mut length = 0;
        for index in first..list.len().min(first + MAX_WINDOW_WORDS) {
            let token = &list[index];
            let folded = tokens.folded(token);
            if !usable(token, folded) || (index > first && !adjacent(&list[index - 1], token, bytes)) {
                break;
            }
            let range = list[first].stem.start..token.stem.end;
            let next_join = joins.partition_point(|&join| join <= range.start);
            if joins.get(next_join).is_some_and(|&join| join < range.end) || !allows(range.start, range.end)
            {
                break;
            }
            if index == first && gate.single {
                let found = heard.entry(folded).or_insert_with(|| {
                    let start = single_matches.len();
                    match_single(folded, terms, &mut scratch, &mut single_matches);
                    start..single_matches.len()
                });
                out.extend(
                    single_matches[found.clone()]
                        .iter()
                        .map(|&term| Candidate { range: range.clone(), written: terms.terms[term].written }),
                );
            }
            if !gate.multi {
                break;
            }
            length += folded.len();
            if length > terms.longest_window {
                break;
            }
            if index > first {
                let lead = *lead.get_or_insert_with(|| first_lead(tokens.folded(&list[first]), &mut scratch.letters));
                let window = Window { tokens, first, last: index, length };
                let before = out.len();
                match_multi(&window, lead, &range, terms, bucketed, &mut scratch, out);
                if out.len() == before {
                    continue;
                }
                // Without language evidence every joined word must be common;
                // a longer window would contain the same word.
                if gate.multi_common_only
                    && !(first..=index).all(|word| {
                        *common[word].get_or_insert_with(|| is_common_word(tokens.folded(&list[word])))
                    })
                {
                    out.truncate(before);
                    break;
                }
                // No window ends in a function word or is half function words.
                let mut function_word =
                    |word: usize| *function[word].get_or_insert_with(|| is_function_word(tokens.folded(&list[word])));
                let function_words = (first..=index).filter(|&word| function_word(word)).count();
                if function_word(index) || 2 * function_words >= index - first + 1 {
                    out.truncate(before);
                }
            }
        }
    }
}

/// Adjacent tokens `first..=last` whose folded letters are joined.
struct Window<'t> {
    tokens: &'t Tokens,
    first: usize,
    last: usize,
    length: usize,
}

impl Window<'_> {
    fn join_into(&self, joined: &mut String) {
        joined.clear();
        for token in &self.tokens.list[self.first..=self.last] {
            joined.push_str(self.tokens.folded(token));
        }
    }

    fn present(&self) -> u32 {
        self.tokens.list[self.first..=self.last].iter().fold(0, |all, token| all | present(self.tokens.folded(token).as_bytes()))
    }
}

/// Reusable buffers for window keys.
#[derive(Default)]
struct Scratch {
    letters: Vec<u8>,
    key: Vec<u8>,
    joined: String,
    rows: [Vec<usize>; 3],
}

impl Scratch {
    fn key(&mut self, folded: &str) -> &[u8] {
        key_into(folded.as_bytes(), &mut self.letters, &mut self.key);
        &self.key
    }
}

/// One spoken word for one listed word: same key, and the heard word is not
/// an ordinary word. There is no spelling budget, so a heard spelling far
/// from the listed one (cattlein for Kotlin) still matches by sound.
fn match_single(folded: &str, terms: &Terms, scratch: &mut Scratch, found: &mut Vec<usize>) {
    if folded.len() < 4 || terms.single.is_empty() || sounds_bound(folded) < terms.fewest_single_sounds {
        return;
    }
    let heard_key = scratch.key(folded);
    let mut common = None;
    for &index in &terms.single {
        let term = &terms.terms[index];
        if folded != term.folded
            && keys_match(heard_key, &term.key)
            && !*common.get_or_insert_with(|| is_common_word(folded))
        {
            found.push(index);
        }
    }
}

/// Two or three spoken words for one listed word or phrase: same key and
/// within the spelling budget of the listed letters. `lead` is the window
/// key's `lead_slot` when already known.
fn match_multi<'a>(
    window: &Window,
    lead: Option<usize>,
    range: &Range<usize>,
    terms: &Terms<'a>,
    bucketed: bool,
    scratch: &mut Scratch,
    out: &mut Vec<Candidate<'a>>,
) {
    let length = window.length;
    let Scratch { letters, key, joined, rows } = scratch;
    let mut keyed = false;
    let key_window = |letters: &mut Vec<u8>, key: &mut Vec<u8>, joined: &mut String| {
        window.join_into(joined);
        key_into(joined.as_bytes(), letters, key);
    };
    // Matching keys share their first symbol, so it selects the only terms
    // the window can match.
    let lead = lead.unwrap_or_else(|| {
        key_window(letters, key, joined);
        keyed = true;
        lead_slot(key.first())
    });
    let mut consider = |index: usize| {
        let term = &terms.terms[index];
        if !keyed {
            key_window(letters, key, joined);
            keyed = true;
        }
        if keys_match(key, &term.key)
            && osa_with(joined.as_bytes(), term.folded.as_bytes(), rows) <= budget(term.folded.len())
        {
            out.push(Candidate { range: range.clone(), written: term.written });
        }
    };
    if bucketed {
        // Term lengths within budget of the window length are contiguous:
        // `length + budget(length)` increases and `length - budget(length)`
        // never decreases. Each spelling edit changes the set of letters
        // present by at most two.
        let group = &terms.by_lead[lead];
        let start = group.partition_point(|&(term_length, ..)| term_length + budget(term_length) < length);
        let mut window_present = None;
        for &(term_length, present, index) in &group[start..] {
            let allowed = budget(term_length);
            if term_length - allowed > length {
                break;
            }
            let window_present = *window_present.get_or_insert_with(|| window.present());
            if (window_present ^ present).count_ones() as usize <= 2 * allowed {
                consider(index);
            }
        }
    } else {
        for (index, term) in terms.terms.iter().enumerate() {
            if length.abs_diff(term.folded.len()) <= budget(term.folded.len()) {
                consider(index);
            }
        }
    }
}
