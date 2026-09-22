use std::collections::VecDeque;

/// Retains only the newest `limit` samples without shifting the existing
/// prefix of a contiguous buffer on every quiet audio block.
pub(crate) fn append(preroll: &mut VecDeque<f32>, input: &[f32], limit: usize) {
    if limit == 0 {
        preroll.clear();
        return;
    }
    if input.len() >= limit {
        preroll.clear();
        preroll.extend(input[input.len() - limit..].iter().copied());
        return;
    }
    let excess = (preroll.len() + input.len()).saturating_sub(limit);
    if excess > 0 {
        preroll.drain(..excess.min(preroll.len()));
    }
    preroll.extend(input.iter().copied());
}

#[cfg(test)]
mod tests {
    use super::append;
    use std::collections::VecDeque;

    #[test]
    fn retains_the_newest_samples_without_exceeding_the_limit() {
        let mut preroll = VecDeque::new();
        append(&mut preroll, &[1.0, 2.0, 3.0], 4);
        append(&mut preroll, &[4.0, 5.0], 4);
        assert_eq!(
            preroll.into_iter().collect::<Vec<_>>(),
            vec![2.0, 3.0, 4.0, 5.0]
        );
    }

    #[test]
    fn oversized_input_replaces_old_audio_with_its_newest_tail() {
        let mut preroll = VecDeque::from(vec![8.0, 9.0]);
        append(&mut preroll, &[1.0, 2.0, 3.0, 4.0, 5.0], 3);
        assert_eq!(preroll.into_iter().collect::<Vec<_>>(), vec![3.0, 4.0, 5.0]);
    }

    #[test]
    fn zero_limit_releases_retained_audio() {
        let mut preroll = VecDeque::from(vec![1.0, 2.0]);
        append(&mut preroll, &[3.0], 0);
        assert!(preroll.is_empty());
    }
}
