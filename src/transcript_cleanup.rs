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
#[cfg(test)]
pub(crate) fn finish(
    raw_text: String,
    language: OutputLanguage,
    preferred_terms: Option<&[String]>,
    corrections: Option<&[crate::personal_corrections::Rule]>,
) -> String {
    finish_with_joins(raw_text, language, preferred_terms, corrections, &[])
}

/// `joins` are byte offsets in `raw_text` where two recognition results were
/// concatenated without alignment; no sound-alike window spans one.
#[cfg(test)]
pub(crate) fn finish_with_joins(
    raw_text: String,
    language: OutputLanguage,
    preferred_terms: Option<&[String]>,
    corrections: Option<&[crate::personal_corrections::Rule]>,
    joins: &[usize],
) -> String {
    finish_body(raw_text, language, preferred_terms, corrections, joins, &[], |_| {})
}

/// The cleanup of every job. `joins` are byte offsets in `raw_text` where two
/// recognition results were concatenated without alignment; no sound-alike window
/// spans one. `request_terms` are the words the caller of a recognition-service
/// request sent for it (`RecognizerIntent.EXTRA_BIASING_STRINGS`), empty otherwise;
/// they apply after the user's list.
pub(crate) fn finish_request(
    raw_text: String,
    language: OutputLanguage,
    preferred_terms: Option<&[String]>,
    corrections: Option<&[crate::personal_corrections::Rule]>,
    joins: &[usize],
    request_terms: &[String],
) -> String {
    finish_body(raw_text, language, preferred_terms, corrections, joins, request_terms, |_| {})
}

/// The final text plus the text after corrections and after preferred words.
#[cfg(test)]
pub(crate) fn finish_snapshots(
    raw_text: String,
    language: OutputLanguage,
    preferred_terms: Option<&[String]>,
    corrections: Option<&[crate::personal_corrections::Rule]>,
    joins: &[usize],
) -> (String, Vec<String>) {
    let mut snapshots = Vec::new();
    let text = finish_body(raw_text, language, preferred_terms, corrections, joins, &[], |text| {
        snapshots.push(text.to_string())
    });
    (text, snapshots)
}

fn finish_body(
    raw_text: String,
    language: OutputLanguage,
    preferred_terms: Option<&[String]>,
    corrections: Option<&[crate::personal_corrections::Rule]>,
    joins: &[usize],
    request_terms: &[String],
    mut snapshot: impl FnMut(&str),
) -> String {
    let mut joins = joins.to_vec();
    joins.sort_unstable();
    joins.dedup();
    // Match explicit rules against the raw assembled recognition result.
    // Joins exist only where long audio fell back to concatenation.
    let (text, protected) = match corrections {
        Some(rules) if joins.is_empty() => {
            crate::personal_corrections::apply_with_ranges(&raw_text, rules)
        }
        Some(rules) => crate::personal_corrections::apply_with_joins(&raw_text, rules, &mut joins),
        None => (raw_text, Vec::new()),
    };
    snapshot(&text);
    // `looks_english` of the current text, once known.
    let mut english_looking = None;
    let (text, protected) = if let Some(terms) = preferred_terms {
        let gate = sound_alike_gate(language, &text, &mut english_looking);
        let (preferred, protected) = if gate.is_open() {
            crate::preferred_terms::apply_with_sound_alike(&text, terms, &protected, gate, &joins)
        } else {
            crate::preferred_terms::apply_with_ranges(&text, terms, &protected)
        };
        if preferred != text {
            english_looking = None;
        }
        (preferred, protected)
    } else {
        (text, protected)
    };
    snapshot(&text);
    // The request's own words last: corrections and the user's matches are protected
    // by now, so a caller's word can only change text the user's rules left alone.
    let (text, protected) = if request_terms.is_empty() {
        (text, protected)
    } else {
        let (requested, protected) = crate::preferred_terms::apply_request(&text, request_terms, &protected);
        if requested != text {
            english_looking = None;
        }
        (requested, protected)
    };
    // Guess only when language evidence is absent; a known non-English language always wins.
    if language == OutputLanguage::English
        || (language == OutputLanguage::Unknown
            && *english_looking.get_or_insert_with(|| crate::english_fillers::looks_english(&text))) {
        crate::english_fillers::remove_with_protected_owned(text, &protected)
    } else {
        text
    }
}

/// Sound-alike keys are English. Without language evidence a single word is
/// matched only in English-looking text, and words are joined only when the
/// whole text is ASCII and each joined word is an English word.
/// `english_looking` caches `looks_english(text)`.
fn sound_alike_gate(
    language: OutputLanguage,
    text: &str,
    english_looking: &mut Option<bool>,
) -> crate::preferred_terms::sound_alike::Gate {
    use crate::preferred_terms::sound_alike::Gate;
    match language {
        OutputLanguage::English => Gate { single: true, multi: true, multi_common_only: false },
        OutputLanguage::Other => Gate { single: false, multi: false, multi_common_only: false },
        OutputLanguage::Unknown => Gate {
            single: *english_looking.get_or_insert_with(|| crate::english_fillers::looks_english(text)),
            multi: text.is_ascii(),
            multi_common_only: true,
        },
    }
}
