//! In-app keyboard for the Typing workspace (Linux desktop).
//!
//! Replaces the system text field with large touch/dwell targets: a message
//! bar (draft with caret, Speak, Clear, Undo-prediction, M+ save-as-phrase,
//! MR history), a saved-phrase completion strip, the word-prediction row, and
//! a QWERTY / symbols key grid with momentary shift and a caret.
//!
//! The caret is a plain character offset owned by the app. All editing
//! helpers here are pure functions over `(draft, caret)` so they stay
//! testable without UI state. Key ids double as typing-surface dwell targets,
//! meaning tap, dwell, select-key, and scanning all share one activation path.

/// Character offset clamped into the draft.
pub fn clamp_caret(draft: &str, caret: usize) -> usize {
    caret.min(draft.chars().count())
}

/// Split the draft around a character-offset caret.
pub fn split_at_caret(draft: &str, caret: usize) -> (&str, &str) {
    let caret = clamp_caret(draft, caret);
    let byte = draft
        .char_indices()
        .nth(caret)
        .map(|(index, _)| index)
        .unwrap_or(draft.len());
    draft.split_at(byte)
}

/// Insert text at the caret; returns the new draft and caret.
pub fn insert_at(draft: &str, caret: usize, text: &str) -> (String, usize) {
    let (before, after) = split_at_caret(draft, caret);
    let mut next = String::with_capacity(draft.len() + text.len());
    next.push_str(before);
    next.push_str(text);
    next.push_str(after);
    let next_caret = clamp_caret(draft, caret) + text.chars().count();
    (next, next_caret)
}

/// Delete the character before the caret (backspace). At the start the draft
/// is unchanged.
pub fn backspace_at(draft: &str, caret: usize) -> (String, usize) {
    let caret = clamp_caret(draft, caret);
    if caret == 0 {
        return (draft.to_string(), 0);
    }
    let (before, after) = split_at_caret(draft, caret);
    let drop_bytes = before.chars().next_back().map(|ch| ch.len_utf8()).unwrap_or(0);
    let kept = &before[..before.len() - drop_bytes];
    let mut next = String::with_capacity(draft.len());
    next.push_str(kept);
    next.push_str(after);
    (next, caret - 1)
}

/// Move the caret by a signed character delta, clamped into the draft.
pub fn move_caret(draft: &str, caret: usize, delta: i32) -> usize {
    let len = draft.chars().count() as i32;
    (clamp_caret(draft, caret) as i32 + delta).clamp(0, len) as usize
}

/// Momentary shift: uppercase ASCII letters for one insertion.
pub fn apply_shift(text: &str, shift: bool) -> String {
    if !shift {
        return text.to_string();
    }
    text.chars()
        .map(|ch| {
            if ch.is_ascii_lowercase() {
                ch.to_ascii_uppercase()
            } else {
                ch
            }
        })
        .collect()
}

/// Render the draft with a visible caret marker.
pub fn render_with_caret(draft: &str, caret: usize) -> String {
    let (before, after) = split_at_caret(draft, caret);
    format!("{before}|{after}")
}

// ---------------------------------------------------------------------------
// Layouts
// ---------------------------------------------------------------------------

/// A key's stable id. Single-character ids insert themselves; the named ids
/// are actions handled by the app.
pub mod id {
    pub const BACKSPACE: &str = "backspace";
    pub const ENTER: &str = "enter";
    pub const SHIFT: &str = "shift";
    pub const SYMBOLS: &str = "sym";
    pub const LETTERS: &str = "abc";
    pub const LEFT: &str = "left";
    pub const RIGHT: &str = "right";
    pub const SPACE: &str = "space";
    pub const SETTINGS: &str = "settings";
}

/// Letter rows (QWERTY). Action keys (backspace, enter, shift) are added by
/// the view as wide keys beside these rows.
pub const LETTER_ROW_TOP: [&str; 10] = ["q", "w", "e", "r", "t", "y", "u", "i", "o", "p"];
pub const LETTER_ROW_HOME: [&str; 9] = ["a", "s", "d", "f", "g", "h", "j", "k", "l"];
pub const LETTER_ROW_BOTTOM: [&str; 7] = ["z", "x", "c", "v", "b", "n", "m"];

/// Symbol rows behind the 123 toggle: digits plus common punctuation.
pub const SYMBOL_ROWS: [[&str; 10]; 3] = [
    ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"],
    ["-", "/", ":", ";", "(", ")", "'", "\"", "@", "_"],
    ["+", "=", "%", "&", "*", "#", "€", "$", "!", "?"],
];

// ---------------------------------------------------------------------------
// Saved-phrase completions (the strip above the word predictions)
// ---------------------------------------------------------------------------

/// Phrases whose text starts with the draft (case-insensitive), excluding an
/// exact match. Empty drafts complete to nothing: the strip is a completer,
/// not a browser (the phrase grid below already browses everything). Pass
/// only visible phrases; hidden ones are filtered by the caller.
pub fn phrase_completions<'a>(
    phrases: impl IntoIterator<Item = &'a str>,
    draft: &str,
    limit: usize,
) -> Vec<String> {
    let needle = draft.trim().to_lowercase();
    if needle.is_empty() {
        return Vec::new();
    }
    let mut out = Vec::new();
    for phrase in phrases {
        if out.len() >= limit {
            break;
        }
        let candidate = phrase.trim();
        if candidate.is_empty() || candidate.to_lowercase() == needle {
            continue;
        }
        if candidate.to_lowercase().starts_with(&needle) {
            out.push(candidate.to_string());
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn insert_moves_caret_past_inserted_text() {
        let (draft, caret) = insert_at("hllo", 1, "e");
        assert_eq!(draft, "hello");
        assert_eq!(caret, 2);
    }

    #[test]
    fn insert_is_unicode_safe() {
        let (draft, caret) = insert_at("hej ", 4, "é");
        assert_eq!(draft, "hej é");
        assert_eq!(caret, 5);
        let (before, after) = split_at_caret(&draft, caret);
        assert_eq!((before, after), ("hej é", ""));
    }

    #[test]
    fn backspace_removes_char_before_caret() {
        let (draft, caret) = backspace_at("hello", 5);
        assert_eq!((draft, caret), ("hell".to_string(), 4));
        let (draft, caret) = backspace_at("hello", 1);
        assert_eq!((draft, caret), ("ello".to_string(), 0));
    }

    #[test]
    fn backspace_at_start_is_a_no_op() {
        assert_eq!(
            backspace_at("hi", 0),
            ("hi".to_string(), 0)
        );
    }

    #[test]
    fn caret_clamps_and_moves() {
        assert_eq!(clamp_caret("hi", 99), 2);
        assert_eq!(move_caret("hi", 0, -1), 0);
        assert_eq!(move_caret("hi", 2, 5), 2);
        assert_eq!(move_caret("hi", 1, -1), 0);
    }

    #[test]
    fn shift_uppercases_letters_only() {
        assert_eq!(apply_shift("a", true), "A");
        assert_eq!(apply_shift(",", true), ",");
        assert_eq!(apply_shift("a", false), "a");
    }

    #[test]
    fn caret_renders_between_splits() {
        assert_eq!(render_with_caret("hi", 1), "h|i");
        assert_eq!(render_with_caret("", 0), "|");
    }

    #[test]
    fn completions_match_draft_prefix_case_insensitively() {
        let phrases = ["Are we finished yet?", "are you coming?", "Goodbye", "are"];
        let out = phrase_completions(phrases, "are ", 6);
        assert_eq!(out, vec!["Are we finished yet?", "are you coming?"]);
    }

    #[test]
    fn completions_exclude_exact_matches_and_empty_drafts() {
        let phrases = ["hello", "hello there"];
        assert!(phrase_completions(phrases, "hello", 6)
            .iter()
            .all(|c| c != "hello"));
        assert!(phrase_completions(phrases, "   ", 6).is_empty());
        assert!(phrase_completions(phrases, "", 6).is_empty());
    }

    #[test]
    fn completions_respect_limit() {
        let phrases = ["a1", "a2", "a3", "a4"];
        assert_eq!(phrase_completions(phrases, "a", 2).len(), 2);
    }

    #[test]
    fn layouts_cover_expected_keys() {
        assert!(LETTER_ROW_TOP.contains(&"q"));
        assert!(LETTER_ROW_HOME.contains(&"l"));
        assert!(LETTER_ROW_BOTTOM.contains(&"z"));
        assert!(SYMBOL_ROWS[0].contains(&"1"));
        assert!(SYMBOL_ROWS[2].contains(&"?"));
    }
}
