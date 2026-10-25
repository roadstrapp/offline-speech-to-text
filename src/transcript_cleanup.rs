//! One final cleanup pass after raw recognition windows have been assembled.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum OutputLanguage { English, Other, Unknown }
impl OutputLanguage {
    pub(crate) fn combine(self, next: Self) -> Self {
        match (self, next) {
            (Self::Other, _) | (_, Self::Other) => Self::Other,
            (Self::Unknown, _) | (_, Self::Unknown) => Self::Unknown,
            _ => Self::English,
        }
    }
}
pub(crate) fn finish(
    raw_text: String,
    language: OutputLanguage,
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
    // Guess only when language evidence is absent; a known non-English language always wins.
    if language == OutputLanguage::English
        || (language == OutputLanguage::Unknown && crate::english_fillers::looks_english(&text)) {
        crate::english_fillers::remove_with_protected_owned(text, &protected)
    } else {
        text
    }
}
