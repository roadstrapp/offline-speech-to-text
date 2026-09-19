//! Privacy-safe, bounded compiler for the app-private custom-term list.
//!
//! The Android editor performs the same validation for immediate feedback,
//! but native code validates again because the file crosses a process and
//! language boundary. Invalid data is ignored as a whole; it is never logged.

use std::collections::HashSet;
use std::path::Path;

pub(crate) const FILE_NAME: &str = "custom_terms";
const MAX_FILE_BYTES: u64 = 4096;
const MAX_TERMS: usize = 32;
const MAX_TERM_CHARS: usize = 80;
const MAX_PROMPT_BYTES: usize = 200;

pub(crate) fn load_prompt(path: &Path) -> Option<String> {
    let metadata = match std::fs::metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return None,
        Err(_) => return None,
    };
    if !metadata.is_file() || metadata.len() > MAX_FILE_BYTES {
        return None;
    }
    let raw = match std::fs::read_to_string(path) {
        Ok(raw) => raw,
        Err(_) => return None,
    };
    match compile_prompt_text(&raw) {
        Ok(prompt) => prompt,
        Err(()) => None,
    }
}

pub(crate) fn compile_prompt_text(raw: &str) -> Result<Option<String>, ()> {
    let mut terms = Vec::new();
    let mut seen = HashSet::new();
    for line in raw.lines() {
        let term = line.split_whitespace().collect::<Vec<_>>().join(" ");
        if term.is_empty() {
            continue;
        }
        if term.contains("<|")
            || term.contains("|>")
            || term.chars().any(char::is_control)
            || term.chars().count() > MAX_TERM_CHARS
        {
            return Err(());
        }
        if seen.insert(term.to_lowercase()) {
            terms.push(term);
            if terms.len() > MAX_TERMS {
                return Err(());
            }
        }
    }
    if terms.is_empty() {
        return Ok(None);
    }
    let prompt = terms.join(", ");
    if prompt.len() > MAX_PROMPT_BYTES {
        return Err(());
    }
    Ok(Some(prompt))
}
