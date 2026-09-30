//! Privacy-safe, bounded compiler for the app-private custom-term list.
//!
//! The Android editor performs the same validation for immediate feedback,
//! but native code validates again because the file crosses a process and
//! language boundary. Invalid data is ignored as a whole; it is never logged.

use std::collections::HashSet;
use std::io::Read;
use std::path::Path;

pub(crate) const FILE_NAME: &str = "custom_terms";
const MAX_FILE_BYTES: u64 = 65536;
const MAX_TERMS: usize = 128;
const MAX_TERM_CHARS: usize = 80;
const MAX_PROMPT_BYTES: usize = 200;

#[cfg(test)]
pub(crate) fn load_prompt(path: &Path) -> Option<String> {
    let terms = load_terms(path)?;
    compile_prompt(&terms)
}

pub(crate) fn load_terms(path: &Path) -> Option<Vec<String>> {
    let metadata = match std::fs::metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return None,
        Err(_) => return None,
    };
    if !metadata.is_file() || metadata.len() > MAX_FILE_BYTES {
        return None;
    }
    let mut raw = String::new();
    std::fs::File::open(path)
        .ok()?
        .take(MAX_FILE_BYTES + 1)
        .read_to_string(&mut raw)
        .ok()?;
    parse_terms(&raw).ok().filter(|terms| !terms.is_empty())
}

#[cfg(test)]
pub(crate) fn compile_prompt_text(raw: &str) -> Result<Option<String>, ()> {
    Ok(compile_prompt(&parse_terms(raw)?))
}

fn parse_terms(raw: &str) -> Result<Vec<String>, ()> {
    if raw.len() as u64 > MAX_FILE_BYTES {
        return Err(());
    }
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
    Ok(terms)
}

pub(crate) fn compile_prompt(terms: &[String]) -> Option<String> {
    let mut prompt = String::new();
    for term in terms {
        let next_len = prompt.len() + if prompt.is_empty() { 0 } else { 2 } + term.len();
        if next_len > MAX_PROMPT_BYTES {
            break;
        }
        if !prompt.is_empty() {
            prompt.push_str(", ");
        }
        prompt.push_str(term);
    }
    if prompt.is_empty() {
        None
    } else {
        Some(prompt)
    }
}
