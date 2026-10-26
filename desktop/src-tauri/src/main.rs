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
    menu::{CheckMenuItem, Menu, MenuItem, PredefinedMenuItem, Submenu},
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

/// Gives the focused app time to read the clipboard after Cmd+V before the previous contents are put back.
const RESTORE_DELAY: Duration = Duration::from_millis(300);

/// Set once the user has been asked about Accessibility access, so a stream of transcripts doesn't nag.
static ASKED_ACCESSIBILITY: AtomicBool = AtomicBool::new(false);

/// The tray's moving parts, shared between the socket callbacks and the menu handler.
#[derive(Clone)]
struct Ui {
    app: AppHandle,
    tray: TrayIcon,
    status: MenuItem<tauri::Wry>,
    recent_menu: Submenu<tauri::Wry>,
    conn: Arc<Mutex<Conn>>,
    history: Arc<Mutex<History>>,
    /// The live pause state. `config.json` only persists it, so an unreadable file can't un-pause the machine.
    paused: Arc<AtomicBool>,
    /// Serializes rebuilds of the recent submenu so two transcripts can't interleave their edits.
    recent_render: Arc<Mutex<()>>,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
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
            let mode_paste = CheckMenuItem::with_id(app, "mode-paste", "Paste", true, mode == Mode::Paste, None::<&str>)?;
            let pause = CheckMenuItem::with_id(app, "pause", "Pause receiving", true, paused, None::<&str>)?;
            let recent_menu = Submenu::with_id_and_items(app, "recent", "Recent transcripts", true, &[&empty_recent(app)?])?;
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
                app: handle.clone(),
                tray: tray.clone(),
                status,
                recent_menu,
                conn: Arc::new(Mutex::new(Conn::Connecting)),
                history: Arc::new(Mutex::new(History::default())),
                paused: Arc::new(AtomicBool::new(paused)),
                recent_render: Arc::new(Mutex::new(())),
            };
            ui.show_state();

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
                        let paused = !ui.paused.fetch_xor(true, Ordering::SeqCst);
                        if let Err(e) = Config::set_paused(&config_path, paused) {
                            eprintln!("pood: could not save pause state: {e}");
                        }
                        let _ = pause.set_checked(paused);
                        ui.show_state();
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
            let conn_ui = ui.clone();
            tauri::async_runtime::spawn(socket::run(
                move || Config::load(&load_path),
                reconnect,
                move |conn| {
                    *lock(&conn_ui.conn) = conn;
                    conn_ui.show_state();
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
    /// Redraws the icon, tooltip and status line from the last connection state and the pause state.
    fn show_state(&self) {
        let paused = self.paused.load(Ordering::SeqCst);
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

    /// Rebuilds the "Recent transcripts" submenu, newest first. The history lock is released before any menu call,
    /// because those wait on the main thread, which may itself be waiting for that lock (a click on a recent item).
    fn show_history(&self) {
        let _render = lock(&self.recent_render);
        let recent = lock(&self.history).recent().to_vec();
        for old in self.recent_menu.items().unwrap_or_default() {
            let _ = self.recent_menu.remove(&old);
        }
        if recent.is_empty() {
            if let Ok(item) = empty_recent(&self.app) {
                let _ = self.recent_menu.append(&item);
            }
        }
        for (n, text) in recent.iter().enumerate() {
            if let Ok(item) = MenuItem::with_id(&self.app, format!("recent-{n}"), history::label(text), true, None::<&str>) {
                let _ = self.recent_menu.append(&item);
            }
        }
    }
}

fn empty_recent(app: &impl Manager<tauri::Wry>) -> tauri::Result<MenuItem<tauri::Wry>> {
    MenuItem::with_id(app, "recent-none", "No transcripts yet", false, None::<&str>)
}

/// Copies a transcript to the clipboard and shows a notification with its start. In paste mode, a fresh transcript is
/// instead pasted into the focused app and the clipboard is then put back as it was; if pasting isn't possible the
/// transcript stays copied and the notification says why.
/// While paused the transcript is ignored entirely: not copied, not announced, not kept in the recent list.
fn deliver(app: &AppHandle, ui: &Ui, config_path: &PathBuf, text: &str, device: &str, fresh: bool) {
    if ui.paused.load(Ordering::SeqCst) {
        return;
    }
    let mode = Config::load(config_path).map(|c| c.mode).unwrap_or_default();
    lock(&ui.history).push(text.to_string());
    ui.show_history();
    let wants_paste = paste::should_paste(mode, fresh);
    let can_paste = wants_paste && paste::can_paste();
    // The previous contents are only worth keeping when the transcript is about to be pasted rather than left copied.
    let copied = arboard::Clipboard::new().and_then(|mut c| {
        let previous = can_paste.then(|| paste::Snapshot::take(&mut c));
        c.set_text(text).map(|()| previous)
    });
    let mut preview: String = text.chars().take(PREVIEW_CHARS).collect();
    if text.chars().count() > PREVIEW_CHARS {
        preview.push('…');
    }
    let title = match &copied {
        Err(e) => format!("From {device} (copy failed: {e})"),
        Ok(_) if wants_paste && !can_paste => format!("Copied from {device} (paste needs Accessibility access)"),
        Ok(_) if can_paste => format!("Pasted from {device}"),
        Ok(_) => format!("Copied from {device}"),
    };
    let _ = app.notification().builder().title(title).body(preview).show();

    match copied {
        Ok(_) if wants_paste && !can_paste => ensure_paste_permission(app),
        Ok(Some(previous)) => {
            let app = app.clone();
            let text = text.to_string();
            std::thread::spawn(move || {
                std::thread::sleep(PASTE_DELAY);
                // Synthesizing key events must happen on the main thread on macOS.
                let (done, pasted) = std::sync::mpsc::channel();
                let _ = app.run_on_main_thread(move || {
                    let _ = done.send(paste::press_paste());
                });
                match pasted.recv() {
                    Ok(Ok(())) => {
                        std::thread::sleep(RESTORE_DELAY);
                        if let Err(e) = previous.restore(&text) {
                            eprintln!("pood: could not restore the clipboard: {e}");
                        }
                    }
                    // Not pasted, so leave the transcript on the clipboard for the user to paste by hand.
                    Ok(Err(e)) => eprintln!("pood: paste failed: {e}"),
                    Err(_) => eprintln!("pood: paste was never run"),
                }
            });
        }
        _ => {}
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
