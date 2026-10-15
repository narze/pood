use crate::config::Mode;
use enigo::{Direction, Enigo, Key, Keyboard, Settings};

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
