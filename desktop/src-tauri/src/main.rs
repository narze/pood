// Pood desktop: a menu bar app that receives transcripts spoken on the phone and puts them on the clipboard.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod config;
mod socket;

use config::Config;
use socket::Conn;
use std::{path::PathBuf, sync::Arc};
use tauri::{
    image::Image,
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::{TrayIcon, TrayIconBuilder},
    AppHandle, Manager, RunEvent,
};
use tauri_plugin_notification::NotificationExt;
use tokio::sync::Notify;

const ICON_CONNECTED: &[u8] = include_bytes!("../icons/tray/connected.png");
const ICON_CONNECTING: &[u8] = include_bytes!("../icons/tray/connecting.png");
const ICON_OFF: &[u8] = include_bytes!("../icons/tray/off.png");

/// Characters of the transcript shown in the notification.
const PREVIEW_CHARS: usize = 80;

fn main() {
    // Both rustls backends end up in the dependency tree, so pick one explicitly.
    let _ = rustls::crypto::ring::default_provider().install_default();
    tauri::Builder::default()
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            let handle = app.handle().clone();
            let config_path = handle.path().app_config_dir()?.join("config.json");
            let reconnect = Arc::new(Notify::new());

            let status = MenuItem::with_id(app, "status", "Starting…", false, None::<&str>)?;
            let menu = Menu::with_items(
                app,
                &[
                    &status,
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
                .on_menu_event({
                    let reconnect = reconnect.clone();
                    let config_path = config_path.clone();
                    move |app, event| match event.id.as_ref() {
                        "reconnect" => reconnect.notify_one(),
                        "config" => open_config(&config_path),
                        "quit" => app.exit(0),
                        _ => {}
                    }
                })
                .build(app)?;

            tauri::async_runtime::spawn(socket::run(
                move || Config::load(&config_path),
                reconnect,
                move |conn| show_state(&tray, &status, &conn),
                move |text, device| deliver(&handle, &text, &device),
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

fn show_state(tray: &TrayIcon, status: &MenuItem<tauri::Wry>, conn: &Conn) {
    let (icon, text) = match conn {
        Conn::Connecting => (ICON_CONNECTING, "Connecting…".to_string()),
        Conn::Connected { user } => (ICON_CONNECTED, format!("Connected as {user}")),
        Conn::Disconnected { reason } => (ICON_OFF, reason.clone()),
    };
    if let Ok(image) = Image::from_bytes(icon) {
        let _ = tray.set_icon(Some(image));
        let _ = tray.set_icon_as_template(true);
    }
    eprintln!("pood: {text}");
    let _ = tray.set_tooltip(Some(format!("Pood: {text}")));
    let _ = status.set_text(text);
}

/// Copies a transcript to the clipboard and shows a notification with its start.
fn deliver(app: &AppHandle, text: &str, device: &str) {
    let copied = arboard::Clipboard::new().and_then(|mut c| c.set_text(text));
    let mut preview: String = text.chars().take(PREVIEW_CHARS).collect();
    if text.chars().count() > PREVIEW_CHARS {
        preview.push('…');
    }
    let title = match copied {
        Ok(()) => format!("Copied from {device}"),
        Err(e) => format!("From {device} (copy failed: {e})"),
    };
    let _ = app.notification().builder().title(title).body(preview).show();
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
