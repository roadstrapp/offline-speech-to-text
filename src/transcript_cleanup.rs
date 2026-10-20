//! One final cleanup pass after raw recognition windows have been assembled.
pub(crate) fn finish(
    raw_text: String,
    english_output: bool,
    preferred_terms: Option<&[String]>,
    corrections: Option<&[crate::personal_corrections::Rule]>,
) -> String {
    // Match explicit rules against the raw assembled recognition result.
    let (text, protected) = if let Some(rules) = corrections {
        crate::personal_corrections::apply_with_ranges(&raw_text, rules)
    } else {
        (raw_text, Vec::new())
    };
    let (text, protected) = if let Some(terms) = preferred_terms {
        crate::preferred_terms::apply_with_ranges(&text, terms, &protected)
    } else {
        (text, protected)
    };
    if english_output {
        crate::english_fillers::remove_with_protected_owned(text, &protected)
    } else {
        text
    }
}
