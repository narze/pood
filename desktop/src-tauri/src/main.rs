// Pood desktop: a menu bar app that receives transcripts spoken on the phone, puts them on the clipboard, and
// (in paste mode) types the fresh ones into the focused app.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod config;
mod history;
mod paste;
mod socket;

use config::{Config, Mode};
use history::History;
use socket::Conn;
use std::{
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, MutexGuard,
    },
    time::Duration,
};
use tauri::{
    image::Image,
    menu::{CheckMenuItem, IsMenuItem, Menu, MenuItem, PredefinedMenuItem, Submenu},
    tray::{TrayIcon, TrayIconBuilder},
    AppHandle, Manager, RunEvent,
};
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons};
use tauri_plugin_notification::NotificationExt;
use tokio::sync::Notify;

const ICON_CONNECTED: &[u8] = include_bytes!("../icons/tray/connected.png");
const ICON_CONNECTING: &[u8] = include_bytes!("../icons/tray/connecting.png");
const ICON_OFF: &[u8] = include_bytes!("../icons/tray/off.png");

/// Characters of the transcript shown in the notification.
const PREVIEW_CHARS: usize = 80;

/// Gives the clipboard a moment to settle before the paste shortcut is sent.
const PASTE_DELAY: Duration = Duration::from_millis(60);

/// Set once the user has been asked about Accessibility access, so a stream of transcripts doesn't nag.
static ASKED_ACCESSIBILITY: AtomicBool = AtomicBool::new(false);

/// The tray's moving parts, shared between the socket callbacks and the menu handler.
#[derive(Clone)]
struct Ui {
    tray: TrayIcon,
    status: MenuItem<tauri::Wry>,
    recent: Vec<MenuItem<tauri::Wry>>,
    conn: Arc<Mutex<Conn>>,
    history: Arc<Mutex<History>>,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

fn is_paused(config_path: &PathBuf) -> bool {
    Config::load(config_path).map(|c| c.paused).unwrap_or(false)
}

fn main() {
    // Both rustls backends end up in the dependency tree, so pick one explicitly.
    let _ = rustls::crypto::ring::default_provider().install_default();
    tauri::Builder::default()
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let handle = app.handle().clone();
            let config_path = handle.path().app_config_dir()?.join("config.json");
            let reconnect = Arc::new(Notify::new());

            let loaded = Config::load(&config_path);
            let mode = loaded.as_ref().map(|c| c.mode).unwrap_or_default();
            let paused = loaded.as_ref().map(|c| c.paused).unwrap_or(false);
            let status = MenuItem::with_id(app, "status", "Starting…", false, None::<&str>)?;
            let mode_copy = CheckMenuItem::with_id(app, "mode-copy", "Copy", true, mode == Mode::Copy, None::<&str>)?;
            let mode_paste = CheckMenuItem::with_id(app, "mode-paste", "Copy + paste", true, mode == Mode::Paste, None::<&str>)?;
            let pause = CheckMenuItem::with_id(app, "pause", "Pause receiving", true, paused, None::<&str>)?;
            let recent = (0..history::CAPACITY)
                .map(|n| MenuItem::with_id(app, format!("recent-{n}"), "", false, None::<&str>))
                .collect::<Result<Vec<_>, _>>()?;
            let recent_items: Vec<&dyn IsMenuItem<tauri::Wry>> = recent.iter().map(|i| i as _).collect();
            let recent_menu = Submenu::with_id_and_items(app, "recent", "Recent transcripts", true, &recent_items)?;
            let menu = Menu::with_items(
                app,
                &[
                    &status,
                    &pause,
                    &recent_menu,
                    &PredefinedMenuItem::separator(app)?,
                    &mode_copy,
                    &mode_paste,
                    &PredefinedMenuItem::separator(app)?,
                    &MenuItem::with_id(app, "reconnect", "Reconnect", true, None::<&str>)?,
                    &MenuItem::with_id(app, "config", "Open config.json…", true, None::<&str>)?,
                    &PredefinedMenuItem::separator(app)?,
                    &MenuItem::with_id(app, "quit", "Quit Pood", true, Some("CmdOrCtrl+Q"))?,
                ],
            )?;

            let tray = TrayIconBuilder::with_id("pood")
                .icon(Image::from_bytes(ICON_CONNECTING)?)
                .icon_as_template(true)
                .tooltip("Pood")
                .menu(&menu)
                .show_menu_on_left_click(true)
                .build(app)?;
            let ui = Ui {
                tray: tray.clone(),
                status,
                recent,
                conn: Arc::new(Mutex::new(Conn::Connecting)),
                history: Arc::new(Mutex::new(History::default())),
            };

            tray.on_menu_event({
                let reconnect = reconnect.clone();
                let config_path = config_path.clone();
                let ui = ui.clone();
                move |app, event| match event.id.as_ref() {
                    id @ ("mode-copy" | "mode-paste") => {
                        let mode = if id == "mode-paste" { Mode::Paste } else { Mode::Copy };
                        if let Err(e) = Config::set_mode(&config_path, mode) {
                            eprintln!("pood: could not save mode: {e}");
                        }
                        let _ = mode_copy.set_checked(mode == Mode::Copy);
                        let _ = mode_paste.set_checked(mode == Mode::Paste);
                        if mode == Mode::Paste {
                            ensure_paste_permission(app);
                        }
                    }
                    "pause" => {
                        let paused = !is_paused(&config_path);
                        if let Err(e) = Config::set_paused(&config_path, paused) {
                            eprintln!("pood: could not save pause state: {e}");
                        }
                        let _ = pause.set_checked(paused);
                        ui.show_state(paused);
                    }
                    id if id.starts_with("recent-") => {
                        let text = id["recent-".len()..]
                            .parse::<usize>()
                            .ok()
                            .and_then(|n| lock(&ui.history).recent().get(n).cloned());
                        if let Some(text) = text {
                            if let Err(e) = arboard::Clipboard::new().and_then(|mut c| c.set_text(text)) {
                                eprintln!("pood: could not copy: {e}");
                            }
                        }
                    }
                    "reconnect" => reconnect.notify_one(),
                    "config" => open_config(&config_path),
                    "quit" => app.exit(0),
                    _ => {}
                }
            });

            let load_path = config_path.clone();
            let conn_path = config_path.clone();
            let conn_ui = ui.clone();
            tauri::async_runtime::spawn(socket::run(
                move || Config::load(&load_path),
                reconnect,
                move |conn| {
                    *lock(&conn_ui.conn) = conn;
                    conn_ui.show_state(is_paused(&conn_path));
                },
                move |text, device, fresh| deliver(&handle, &ui, &config_path, &text, &device, fresh),
            ));
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("failed to build Pood")
        .run(|_, event| {
            // There are no windows; keep running until Quit.
            if let RunEvent::ExitRequested { api, code: None, .. } = event {
                api.prevent_exit();
            }
        });
}

impl Ui {
    /// Redraws the icon, tooltip and status line from the last connection state.
    fn show_state(&self, paused: bool) {
        let conn = lock(&self.conn).clone();
        let (icon, text) = match &conn {
            Conn::Connecting => (ICON_CONNECTING, "Connecting…".to_string()),
            Conn::Connected { user } => (ICON_CONNECTED, format!("Connected as {user}")),
            Conn::Disconnected { reason } => (ICON_OFF, reason.clone()),
        };
        let text = if paused { format!("Paused · {text}") } else { text };
        if let Ok(image) = Image::from_bytes(icon) {
            let _ = self.tray.set_icon(Some(image));
            let _ = self.tray.set_icon_as_template(true);
        }
        eprintln!("pood: {text}");
        let _ = self.tray.set_tooltip(Some(format!("Pood: {text}")));
        let _ = self.status.set_text(text);
    }

    /// Fills the "Recent transcripts" menu items, newest first; unused slots stay disabled.
    fn show_history(&self) {
        let history = lock(&self.history);
        let recent = history.recent();
        for (n, item) in self.recent.iter().enumerate() {
            match recent.get(n) {
                Some(text) => {
                    let _ = item.set_text(history::label(text));
                    let _ = item.set_enabled(true);
                }
                None => {
                    let _ = item.set_text(if n == 0 { "No transcripts yet" } else { "" });
                    let _ = item.set_enabled(false);
                }
            }
        }
    }
}

/// Copies a transcript to the clipboard and shows a notification with its start. In paste mode, a fresh transcript is
/// then pasted into the focused app; if that isn't possible it stays copied and the notification says why.
/// While paused the transcript is ignored entirely: not copied, not announced, not kept in the recent list.
fn deliver(app: &AppHandle, ui: &Ui, config_path: &PathBuf, text: &str, device: &str, fresh: bool) {
    let config = Config::load(config_path);
    if config.as_ref().map(|c| c.paused).unwrap_or(false) {
        return;
    }
    let mode = config.map(|c| c.mode).unwrap_or_default();
    lock(&ui.history).push(text.to_string());
    ui.show_history();
    let copied = arboard::Clipboard::new().and_then(|mut c| c.set_text(text));
    let mut preview: String = text.chars().take(PREVIEW_CHARS).collect();
    if text.chars().count() > PREVIEW_CHARS {
        preview.push('…');
    }
    let wants_paste = copied.is_ok() && paste::should_paste(mode, fresh);
    let can_paste = wants_paste && paste::can_paste();
    let title = match &copied {
        Err(e) => format!("From {device} (copy failed: {e})"),
        Ok(()) if wants_paste && !can_paste => format!("Copied from {device} (paste needs Accessibility access)"),
        Ok(()) if can_paste => format!("Pasted from {device}"),
        Ok(()) => format!("Copied from {device}"),
    };
    let _ = app.notification().builder().title(title).body(preview).show();

    if wants_paste && !can_paste {
        ensure_paste_permission(app);
    } else if can_paste {
        let app = app.clone();
        std::thread::spawn(move || {
            std::thread::sleep(PASTE_DELAY);
            // Synthesizing key events must happen on the main thread on macOS.
            let _ = app.run_on_main_thread(|| {
                if let Err(e) = paste::press_paste() {
                    eprintln!("pood: paste failed: {e}");
                }
            });
        });
    }
}

/// When pasting isn't allowed yet, says so once and offers to open the pane where the user grants it.
fn ensure_paste_permission(app: &AppHandle) {
    if paste::can_paste() || ASKED_ACCESSIBILITY.swap(true, Ordering::SeqCst) {
        return;
    }
    app.dialog()
        .message("Pood needs Accessibility access to paste into other apps. Until it is granted, transcripts are only copied.\n\nAdd Pood under Privacy & Security → Accessibility, then switch it on.")
        .title("Allow Pood to paste")
        .buttons(MessageDialogButtons::OkCancelCustom("Open Settings".into(), "Not Now".into()))
        .show(|open| {
            if open {
                paste::open_accessibility_settings();
            }
        });
}

fn open_config(path: &PathBuf) {
    let _ = Config::load(path); // make sure it exists
    #[cfg(target_os = "macos")]
    let _ = std::process::Command::new("open").arg("-t").arg(path).spawn();
    #[cfg(target_os = "windows")]
    let _ = std::process::Command::new("notepad").arg(path).spawn();
    #[cfg(target_os = "linux")]
    let _ = std::process::Command::new("xdg-open").arg(path).spawn();
}
