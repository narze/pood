/// How many transcripts the menu keeps.
pub const CAPACITY: usize = 5;

/// Characters of a transcript shown as a menu item.
const LABEL_CHARS: usize = 40;

/// The most recent transcripts received on this machine, newest first.
#[derive(Default)]
pub struct History(Vec<String>);

impl History {
    pub fn push(&mut self, text: String) {
        self.0.insert(0, text);
        self.0.truncate(CAPACITY);
    }

    pub fn recent(&self) -> &[String] {
        &self.0
    }
}

/// A transcript squeezed onto one menu line.
pub fn label(text: &str) -> String {
    let line = text.split_whitespace().collect::<Vec<_>>().join(" ");
    let mut out: String = line.chars().take(LABEL_CHARS).collect();
    if line.chars().count() > LABEL_CHARS {
        out.push('…');
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_the_five_newest_first() {
        let mut history = History::default();
        assert!(history.recent().is_empty());
        for n in 1..=7 {
            history.push(format!("transcript {n}"));
        }
        assert_eq!(
            history.recent(),
            ["transcript 7", "transcript 6", "transcript 5", "transcript 4", "transcript 3"]
        );
    }

    #[test]
    fn labels_are_one_truncated_line() {
        assert_eq!(label("short"), "short");
        assert_eq!(label("two\nlines  here"), "two lines here");
        let long = "x".repeat(60);
        assert_eq!(label(&long), format!("{}…", "x".repeat(40)));
        assert_eq!(label(&"y".repeat(40)), "y".repeat(40));
    }
}
