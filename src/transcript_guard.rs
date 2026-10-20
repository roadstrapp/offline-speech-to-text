//! Shared literal protection for model-independent transcript cleanup.
//! Ranges use the input's UTF-8 offsets; scan each immutable input afresh.
use std::ops::Range;

pub(crate) struct ProtectedText {
    ranges: Vec<Range<usize>>,
}

impl ProtectedText {
    pub(crate) fn new(text: &str, explicit: &[Range<usize>]) -> Self {
        let mut ranges = explicit.to_vec();
        let mut quote: Option<(char, usize)> = None;
        let mut previous = None;
        let mut chars = text.char_indices().peekable();
        while let Some((offset, ch)) = chars.next() {
            let next = chars.peek().map(|(_, value)| *value);
            let internal =
                previous.is_some_and(is_word_character) && next.is_some_and(is_word_character);
            if let Some((delimiter, start)) = quote {
                let closes = match delimiter {
                    '“' => ch == '”',
                    '‘' => ch == '’' && !internal,
                    '\'' => ch == '\'' && !internal,
                    _ => ch == delimiter,
                };
                if closes {
                    ranges.push(start..offset + ch.len_utf8());
                    quote = None;
                }
            } else if matches!(ch, '"' | '“' | '`')
                || (matches!(ch, '\'' | '‘')
                    && !previous.is_some_and(is_word_character)
                    && next.is_some())
            {
                quote = Some((ch, offset));
            }
            previous = Some(ch);
        }
        if let Some((_, start)) = quote {
            ranges.push(start..text.len());
        }
        let mut offset = 0;
        for token in text.split_inclusive(char::is_whitespace) {
            if protected_token(token) {
                ranges.push(offset..offset + token.len());
            }
            offset += token.len();
        }
        ranges.sort_by_key(|range| range.start);
        let mut merged: Vec<Range<usize>> = Vec::new();
        for range in ranges {
            if let Some(last) = merged.last_mut().filter(|last| range.start <= last.end) {
                last.end = last.end.max(range.end);
            } else {
                merged.push(range);
            }
        }
        Self { ranges: merged }
    }

    pub(crate) fn allows(&self, start: usize, end: usize) -> bool {
        let index = self.ranges.partition_point(|range| range.end <= start);
        self.ranges
            .get(index)
            .is_none_or(|range| range.start >= end)
    }
}

pub(crate) fn is_word_character(ch: char) -> bool {
    ch.is_alphanumeric() || ch == '_'
}

pub(crate) fn left_boundary(text: &str, offset: usize) -> bool {
    !text[..offset]
        .chars()
        .next_back()
        .is_some_and(is_word_character)
}

pub(crate) fn right_boundary(text: &str, offset: usize) -> bool {
    !text[offset..].chars().next().is_some_and(is_word_character)
}

/// Match lowercase scalars without slicing a Unicode lowercase expansion.
pub(crate) fn match_folded(text: &str, start: usize, folded: &str) -> Option<usize> {
    let mut expected = folded.chars();
    for (relative, ch) in text[start..].char_indices() {
        for lower in ch.to_lowercase() {
            if expected.next()? != lower {
                return None;
            }
        }
        if expected.clone().next().is_none() {
            return Some(start + relative + ch.len_utf8());
        }
    }
    None
}

fn protected_token(token: &str) -> bool {
    let mut chars = token.chars().peekable();
    let mut previous = None;
    let mut dotted = false;
    while let Some(ch) = chars.next() {
        if ch == '.'
            && previous.is_some_and(char::is_alphanumeric)
            && chars.peek().is_some_and(|next| next.is_alphanumeric())
        {
            dotted = true;
            break;
        }
        previous = Some(ch);
    }
    token.contains("://")
        || token.contains('@')
        || token.contains('/')
        || token.contains('\\')
        || token.contains('`')
        || token
            .get(..4)
            .is_some_and(|prefix| prefix.eq_ignore_ascii_case("www."))
        || dotted
}
