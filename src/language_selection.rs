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

/// Compare model capability tags with persisted primary language codes.
pub(crate) fn supports_all(supported: &[String], allowed: &[String]) -> bool {
    let normalized: Vec<String> = supported.iter().flat_map(|tag| parse_allowed(tag))
        .map(|code| recognition_code(&code).to_string()).collect();
    allowed.iter().all(|code| normalized.iter().any(|supported| supported == recognition_code(code)))
}

pub(crate) fn english_only(supported: &[String]) -> bool {
    supported.len() == 1 && parse_allowed(&supported[0]) == ["en"]
}

/// Loading policy for a saved multi-language list. The speech model remains the
/// authority on supported languages; an unsupported list never becomes auto-detection.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ModelLanguagePolicy { SingleHint, IgnoresList, EnglishOnly, Detect }

pub(crate) fn model_policy(arch: &str, supported: Option<&[String]>, allowed: &[String])
    -> Result<ModelLanguagePolicy, &'static str>
{
    use ModelLanguagePolicy::*;
    if allowed.len() < 2 { return Ok(SingleHint); }
    let english_only = supported.is_some_and(english_only);
    if english_only {
        return if allowed.iter().any(|code| code == "en") { Ok(EnglishOnly) }
            else { Err("This model supports only English, which is outside your language list") };
    }
    if arch == "parakeet" { return Ok(IgnoresList); }
    let supported = supported.ok_or("This model cannot confirm support for the chosen languages")?;
    if !supports_all(supported, allowed) {
        return Err("Choose languages supported by this speech model");
    }
    Ok(Detect)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn model_switch_policy_preserves_language_restrictions() {
        use ModelLanguagePolicy::*;
        let chosen = parse_allowed("en,sk");
        let canary = vec!["en-US".into(), "SK".into()];
        assert_eq!(model_policy("canary", Some(&canary), &chosen), Ok(Detect));
        assert_eq!(model_policy("canary", None, &chosen), Err("This model cannot confirm support for the chosen languages"));
        assert!(model_policy("canary", Some(&["en".into(), "de".into()]), &chosen).is_err());
        for tag in ["en", "en-US", "EN_us"] {
            assert!(english_only(&[tag.into()]));
            assert_eq!(model_policy("whisper", Some(&[tag.into()]), &chosen), Ok(EnglishOnly));
        }
        assert!(model_policy("whisper", Some(&["en".into()]), &parse_allowed("sk,de")).is_err());
        assert_eq!(model_policy("parakeet", None, &chosen), Ok(IgnoresList));
        assert_eq!(model_policy("canary", None, &[]), Ok(SingleHint));
        assert_eq!(model_policy("canary", None, &parse_allowed("sk")), Ok(SingleHint));
    }

    #[test]
    fn capability_tags_accept_regions_case_and_legacy_hebrew() {
        let supported = vec!["en-US".into(), "SK".into(), "pt_BR".into(), "iw".into()];
        assert!(supports_all(&supported, &parse_allowed("en,sk,pt,he")));
        assert!(supports_all(&vec!["he".into()], &parse_allowed("iw")));
        assert!(!supports_all(&supported, &parse_allowed("en,pl")));
        assert!(!supports_all(&[], &parse_allowed("en,sk")));
    }

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
