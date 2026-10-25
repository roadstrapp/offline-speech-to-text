//! Which languages the user speaks, and how a detected one becomes a recognition hint.
//!
//! Detection itself lives in the engine; everything here is plain string handling.

/// Most languages the setting accepts. Two to four is the realistic case; the cap keeps the
/// config file and the detector's allowed set small.
pub(crate) const MAX_ALLOWED: usize = 8;

/// Reads a comma- or whitespace-separated list of language tags (`en`, `de-DE`, ...) into
/// distinct lowercase primary subtags, in the order given. Invalid entries are skipped.
pub(crate) fn parse_allowed(config: &str) -> Vec<String> {
    let mut languages: Vec<String> = Vec::new();
    for entry in config.split(|c: char| c == ',' || c.is_whitespace()) {
        let primary = entry
            .split(['-', '_'])
            .next()
            .unwrap_or("")
            .to_ascii_lowercase();
        let valid = (2..=3).contains(&primary.len()) && primary.bytes().all(|b| b.is_ascii_lowercase());
        if valid && !languages.contains(&primary) && languages.len() < MAX_ALLOWED {
            languages.push(primary);
        }
    }
    languages
}

/// The label the language-ID model uses for a language code. It predates the current ISO 639-1
/// code for Hebrew.
pub(crate) fn detector_label(code: &str) -> &str {
    match code {
        "he" => "iw",
        other => other,
    }
}

/// Inverse of [`detector_label`]: the language code recognition models understand.
pub(crate) fn recognition_code(label: &str) -> &str {
    match label {
        "iw" => "he",
        other => other,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_tags_into_distinct_primary_subtags() {
        assert_eq!(parse_allowed("en,de"), ["en", "de"]);
        assert_eq!(parse_allowed(" en-US de_DE ,EN-gb"), ["en", "de"]);
        assert!(parse_allowed("auto").is_empty());
        assert_eq!(parse_allowed("yue, haw"), ["yue", "haw"]);
    }

    #[test]
    fn skips_invalid_entries_and_caps_the_list() {
        assert!(parse_allowed("").is_empty());
        assert!(parse_allowed(",,x,1234,e1").is_empty());
        let many = "aa,bb,cc,dd,ee,ff,gg,hh,ii,jj";
        assert_eq!(parse_allowed(many).len(), MAX_ALLOWED);
    }

    #[test]
    fn hebrew_maps_both_ways() {
        assert_eq!(detector_label("he"), "iw");
        assert_eq!(recognition_code("iw"), "he");
        assert_eq!(detector_label("de"), "de");
        assert_eq!(recognition_code("de"), "de");
    }
}
