# Pood desktop

A menu bar app that receives every transcript you speak on the phone and copies it to the clipboard, with a
notification. It connects to the gateway's `/v1/desktop` channel (see `../PROTOCOL.md`).

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
  "name": "MacBook"
}
```

- **`url`**: the same gateway URL the phone uses.
- **`name`**: shown on the phone as "→ MacBook ✓". Defaults to the hostname.
- **`token`** (optional): only needed when the gateway isn't reached through `tailscale serve`. On the tailnet, the
  laptop is identified by its Tailscale login, which must be the same user as the phone.

## Menu bar icon

| Icon | State |
|---|---|
| Solid person speaking | Connected |
| Outline person speaking | Connecting |
| Struck-through person speaking | Disconnected; the menu shows why |
