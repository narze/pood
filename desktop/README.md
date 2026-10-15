# Pood desktop

A menu bar app that receives every transcript you speak on the phone and copies it to the clipboard, with a
notification. In paste mode it also types the transcript into whatever app you are focused on. It connects to the gateway's `/v1/desktop` channel (see `../PROTOCOL.md`).

## Build (macOS)

Requires Rust and Bun.

```sh
bun install
bun run build    # → src-tauri/target/release/bundle/macos/Pood.app
bun run dev      # run unbundled; state changes are logged to stderr
```

The app is unsigned. If macOS refuses to open a copy downloaded from elsewhere, right-click it and choose **Open**.

## Configure

On first run, the app writes `~/Library/Application Support/dev.narze.pood.desktop/config.json`. Open it from the
menu bar (**Open config.json…**), then choose **Reconnect**:

```json
{
  "url": "https://server.tailnet.ts.net:8790",
  "name": "MacBook",
  "mode": "copy"
}
```

- **`url`**: the same gateway URL the phone uses.
- **`name`**: shown on the phone as "→ MacBook ✓". Defaults to the hostname.
- **`mode`**: `"copy"` (default) or `"paste"`. Also switchable from the menu bar, which saves it here.
- **`token`** (optional): only needed when the gateway isn't reached through `tailscale serve`. On the tailnet, the
  laptop is identified by its Tailscale login, which must be the same user as the phone.

## Menu bar icon

| Icon | State |
|---|---|
| Solid mic | Connected |
| Outline mic | Connecting |
| Struck-through mic | Disconnected; the menu shows why |

## Paste mode

Choose **Copy + paste** in the menu bar. Every transcript is still copied; a *fresh* one is then pasted into the focused
app with Cmd+V. A transcript is fresh when the gateway broadcasts it within 10 s of you stopping speaking, so anything
that arrives late, or is re-sent from history, is copied but never pasted.

On macOS, pasting needs Accessibility permission. If it is missing, the app says so, offers to open the right Settings
pane, and keeps copying meanwhile. Grant it under **Privacy & Security → Accessibility**. Because the app is unsigned,
each rebuild counts as a new app and the grant has to be given again.
