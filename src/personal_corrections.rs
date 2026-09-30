//! Bounded exact transcript corrections shared by every model family.
//!
//! Recognition hints are backend-specific. These rules are deliberately
//! post-processing instead: Parakeet and other models get the same explicit,
//! opt-in behavior without pretending that the runtime supports hotwords.
//! Invalid data is ignored as a whole and rule content is never logged.

use crate::transcript_guard::{left_boundary, match_folded, right_boundary, ProtectedText};
use std::collections::HashSet;
use std::io::Read;
use std::path::Path;

pub(crate) const FILE_NAME: &str = "personal_corrections";
pub(crate) const ENABLED_FILE_NAME: &str = "personal_corrections_enabled";
const MAX_FILE_BYTES: u64 = 8192;
const MAX_RULES: usize = 32;
const MAX_SIDE_CHARS: usize = 80;
const MAX_TOTAL_EXPANSION_BYTES: usize = 4096;

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Rule {
    heard_folded: String,
    heard_char_count: usize,
    written: String,
}

pub(crate) fn load(files_dir: &Path) -> Option<Vec<Rule>> {
    if !files_dir.join(ENABLED_FILE_NAME).is_file() {
        return None;
    }
    let path = files_dir.join(FILE_NAME);
    let metadata = std::fs::metadata(&path).ok()?;
    if !metadata.is_file() || metadata.len() > MAX_FILE_BYTES {
        return None;
    }
    let mut raw = String::new();
    std::fs::File::open(path)
        .ok()?
        .take(MAX_FILE_BYTES + 1)
        .read_to_string(&mut raw)
        .ok()?;
    parse(&raw).ok().filter(|rules| !rules.is_empty())
}

pub(crate) fn parse(raw: &str) -> Result<Vec<Rule>, ()> {
    if raw.len() as u64 > MAX_FILE_BYTES {
        return Err(());
    }
    let mut rules = Vec::new();
    let mut seen = HashSet::new();
    for line in raw.lines() {
        if contains_unsafe_character(line) {
            return Err(());
        }
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let mut pieces = trimmed.split("=>");
        let heard = normalize(pieces.next().ok_or(())?);
        let written = normalize(pieces.next().ok_or(())?);
        if pieces.next().is_some()
            || heard.is_empty()
            || written.is_empty()
            || heard.chars().count() > MAX_SIDE_CHARS
            || written.chars().count() > MAX_SIDE_CHARS
            || contains_unsafe_character(&heard)
            || contains_unsafe_character(&written)
            || digits(&heard) != digits(&written)
        {
            return Err(());
        }
        let heard_char_count = heard.chars().count();
        let heard_folded = heard.to_lowercase();
        if !seen.insert(heard_folded.clone()) {
            return Err(());
        }
        rules.push(Rule {
            heard_folded,
            heard_char_count,
            written,
        });
        if rules.len() > MAX_RULES {
            return Err(());
        }
    }
    // Longest exact source wins when rules overlap. The stable tie break keeps
    // the user's file order for sources with the same character length.
    rules.sort_by_key(|rule| std::cmp::Reverse(rule.heard_char_count));
    Ok(rules)
}

#[cfg(test)]
pub(crate) fn apply(text: &str, rules: &[Rule]) -> String {
    apply_with_ranges(text, rules).0
}

/// Return replacement ranges so later spelling cleanup preserves exact rules.
pub(crate) fn apply_with_ranges(
    text: &str,
    rules: &[Rule],
) -> (String, Vec<std::ops::Range<usize>>) {
    if text.is_empty() || rules.is_empty() {
        return (text.to_string(), Vec::new());
    }
    let mut result = String::with_capacity(text.len());
    let mut protected = Vec::new();
    let mut offset = 0;
    let guard = ProtectedText::new(text, &[]);
    while offset < text.len() {
        let current = text[offset..]
            .chars()
            .next()
            .expect("valid character boundary");
        let replacement = rules.iter().find_map(|rule| {
            match_rule(text, offset, rule)
                .filter(|(consumed, _)| guard.allows(offset, offset + consumed))
        });
        if let Some((consumed, written)) = replacement {
            let start = result.len();
            result.push_str(written);
            protected.push(start..result.len());
            offset += consumed;
            if result.len() > text.len().saturating_add(MAX_TOTAL_EXPANSION_BYTES) {
                return (text.to_string(), Vec::new());
            }
        } else {
            result.push(current);
            offset += current.len_utf8();
        }
    }
    (result, protected)
}

fn match_rule<'a>(text: &str, offset: usize, rule: &'a Rule) -> Option<(usize, &'a str)> {
    if !left_boundary(text, offset) {
        return None;
    }
    let end = match_folded(text, offset, &rule.heard_folded)?;
    right_boundary(text, end).then_some((end - offset, rule.written.as_str()))
}

fn normalize(value: &str) -> String {
    value.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn contains_unsafe_character(value: &str) -> bool {
    value.chars().any(|character| {
        character.is_control() || matches!(character, '"' | '\'' | '‘' | '’' | '“' | '”')
    })
}

fn digits(value: &str) -> String {
    value
        .chars()
        .filter(|character| character.is_numeric())
        .collect()
}
