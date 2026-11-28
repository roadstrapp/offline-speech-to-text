//! Privacy-safe, bounded compiler for the app-private custom-term list.
//!
//! The Android editor performs the same validation for immediate feedback,
//! but native code validates again because the file crosses a process and
//! language boundary. Invalid data is ignored as a whole; it is never logged.
//!
//! A caller of the recognition service may also send words for one request
//! (`RecognizerIntent.EXTRA_BIASING_STRINGS`). They are bounded and validated
//! here a second time, entry by entry, live only in that request's job, and
//! are never written, logged or returned. They never replace or reorder the
//! user's own list.

use std::collections::HashSet;
use std::io::Read;
use std::path::Path;

pub(crate) const FILE_NAME: &str = "custom_terms";
const MAX_FILE_BYTES: u64 = 65536;
const MAX_TERMS: usize = 128;
const MAX_TERM_CHARS: usize = 80;
const MAX_PROMPT_BYTES: usize = 200;
/// Words accepted from one request, in the caller's order (its priority). A keyboard's
/// personal dictionary can hold thousands; past the first few hundred a word adds matching
/// work for every later word of the transcript and no recognition gain.
pub(crate) const MAX_REQUEST_TERMS: usize = 1000;
/// UTF-8 bytes of all accepted request words together.
pub(crate) const MAX_REQUEST_BYTES: usize = 16 * 1024;

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

/// The words of one request, one per line as `BiasingWords` (Java) joined them. Unlike
/// the user's own file, an unusable entry is dropped alone: another app's list is not
/// under the user's control and one bad entry must not disable the rest. Order is kept;
/// everything after the count or byte bound is ignored.
pub(crate) fn parse_request_terms(raw: &str) -> Vec<String> {
    let mut terms = Vec::new();
    let mut seen = HashSet::new();
    let mut bytes = 0usize;
    for line in raw.lines() {
        if terms.len() == MAX_REQUEST_TERMS {
            break;
        }
        let term = line.split_whitespace().collect::<Vec<_>>().join(" ");
        if term.is_empty()
            || term.contains("<|")
            || term.contains("|>")
            || term.chars().any(char::is_control)
            || term.chars().count() > MAX_TERM_CHARS
        {
            continue;
        }
        let key = term.to_lowercase();
        if seen.contains(&key) {
            continue;
        }
        if bytes + term.len() > MAX_REQUEST_BYTES {
            break;
        }
        seen.insert(key);
        bytes += term.len();
        terms.push(term);
    }
    terms
}

/// The Whisper prompt for one job: the user's own words exactly as `compile_prompt`
/// places them, then the request's words in the caller's order while they fit in the
/// same bound. A request can therefore never push one of the user's words out.
pub(crate) fn compile_request_prompt(own: Option<&[String]>, request: &[String]) -> Option<String> {
    let mut prompt = own.and_then(compile_prompt).unwrap_or_default();
    let listed: HashSet<String> = own.unwrap_or_default().iter().map(|term| term.to_lowercase()).collect();
    for term in request {
        if listed.contains(&term.to_lowercase()) {
            continue;
        }
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
