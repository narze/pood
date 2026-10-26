use crate::config::Mode;
use arboard::{Clipboard, ImageData};
use enigo::{Direction, Enigo, Key, Keyboard, Settings};

/// What was on the clipboard before a paste borrowed it, so it can be put back afterwards.
pub enum Snapshot {
    Empty,
    Text(String),
    Image(ImageData<'static>),
}

impl Snapshot {
    /// Reads the clipboard as text, else as an image. Anything else (files, rich text only) is not kept.
    pub fn take(clipboard: &mut Clipboard) -> Snapshot {
        if let Ok(text) = clipboard.get_text() {
            return Snapshot::Text(text);
        }
        match clipboard.get_image() {
            Ok(image) => Snapshot::Image(image.to_owned_img()),
            Err(_) => Snapshot::Empty,
        }
    }

    /// Puts the snapshot back, unless the clipboard no longer holds `pasted`: then someone copied something since,
    /// and that wins.
    pub fn restore(self, pasted: &str) -> Result<(), String> {
        let mut clipboard = Clipboard::new().map_err(|e| e.to_string())?;
        if clipboard.get_text().ok().as_deref() != Some(pasted) {
            return Ok(());
        }
        match self {
            Snapshot::Empty => clipboard.clear(),
            Snapshot::Text(text) => clipboard.set_text(text),
            Snapshot::Image(image) => clipboard.set_image(image),
        }
        .map_err(|e| e.to_string())
    }
}

/// Only fresh transcripts are typed into the focused app; anything older would land somewhere the user no longer expects.
pub fn should_paste(mode: Mode, fresh: bool) -> bool {
    mode == Mode::Paste && fresh
}

/// Whether synthetic key presses will be delivered. macOS silently drops them without the Accessibility grant.
pub fn can_paste() -> bool {
    #[cfg(target_os = "macos")]
    {
        #[link(name = "ApplicationServices", kind = "framework")]
        extern "C" {
            fn AXIsProcessTrusted() -> bool;
        }
        // SAFETY: a plain C call with no arguments.
        unsafe { AXIsProcessTrusted() }
    }
    #[cfg(not(target_os = "macos"))]
    true
}

/// Opens the system pane where the user grants this app Accessibility access.
pub fn open_accessibility_settings() {
    #[cfg(target_os = "macos")]
    let _ = std::process::Command::new("open")
        .arg("x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")
        .spawn();
}

/// Presses Cmd+V (Ctrl+V elsewhere). On macOS, call this from the main thread.
pub fn press_paste() -> Result<(), String> {
    #[cfg(target_os = "macos")]
    let (modifier, v) = (Key::Meta, Key::Other(9)); // kVK_ANSI_V: a physical key, so it works on any keyboard layout
    #[cfg(not(target_os = "macos"))]
    let (modifier, v) = (Key::Control, Key::Unicode('v'));

    let mut enigo = Enigo::new(&Settings::default()).map_err(|e| e.to_string())?;
    enigo.key(modifier, Direction::Press).map_err(|e| e.to_string())?;
    let result = enigo.key(v, Direction::Click).map_err(|e| e.to_string());
    let _ = enigo.key(modifier, Direction::Release); // never leave the modifier stuck down
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pastes_only_fresh_transcripts_in_paste_mode() {
        assert!(should_paste(Mode::Paste, true));
        assert!(!should_paste(Mode::Paste, false));
        assert!(!should_paste(Mode::Copy, true));
        assert!(!should_paste(Mode::Copy, false));
    }
}
