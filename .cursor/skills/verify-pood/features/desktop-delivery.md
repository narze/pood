# Desktop delivery

Every non-empty transcript the user speaks on any phone is pushed live to every desktop receiver logged in as the same user. The desktop copies it to the clipboard (and pastes it in paste mode). The phone shows which desktops received it ("→ MacBook ✓") from `final.delivered`.

## Sub-features

- `desktop-hello`: connecting to `/v1/desktop?name=<name>` yields `hello` with the user.
- `desktop-broadcast`: each non-empty final produces one `transcript` frame (`id`, `text`, `device`, `createdAt`) on every connected desktop of that user.
- `desktop-delivered`: the phone's `final.delivered` lists exactly the names of the desktops it was sent to, and `[]` when none are connected.
- `desktop-scoped`: desktops of other users receive nothing.
- `desktop-offline`: a desktop that connects later does not get earlier transcripts; they remain in history only.
- `desktop-tray` (tray app only): the icon shows connected, connecting or struck-through. Menu items: `Copy` / `Copy + paste`, `Pause receiving`, `Recent transcripts`, `Reconnect`, `Open config.json…`, `Quit Pood`.

## How to get to it (user POV)

- Run the Pood menu bar app (`desktop/`), set `url` in its `config.json`, and choose **Reconnect**. Then speak on the phone.
- `bun scripts/desktop.ts <ws-url> <name> <token>`, which the server README documents as a receiver.

## Driving it with pood-verify

Preconditions:

- `$V doctor` passes. No receivers are running for this run yet.

- **No desktop.** Run `$V clip deliv-none Samantha "Nobody is listening yet."` and `$V speak deliv-none`. The final has `"delivered":[]`.
- **Two desktops.** Run `$V listen MacBook-verify` and `$V listen Studio-verify`. Each prints `connected as token:veri (<name>)`.
- **Broadcast.** Run `$V clip deliv-two Samantha "Send this to both desktops."` and `$V speak deliv-two`. The final has `"delivered":["MacBook-verify","Studio-verify"]` (in either order). Both `desktop-MacBook-verify.log` and `desktop-Studio-verify.log` gain the line `[<time> cli] Send this to both desktops.`, and neither contains `Nobody is listening yet.` (`desktop-offline`).
- **Same id everywhere.** Run `$V api GET /v1/history`. The newest item's `id` equals the final's `id`.
- **Other user is isolated.** This needs a second token, which is a gateway config change. Add a second token to the run's `pood.toml`, then `cleanup` and `launch`. Or use the Tailscale header path from `auth.md`: a raw Bun desktop socket sent with `Tailscale-User-Login` for a listed user gets no frame when `token:veri` speaks. If you skip this step, report `desktop-scoped` as unverified.

## Gotchas

- `scripts/desktop.ts` reconnects with backoff forever. Only `$V cleanup` (which kills the recorded pids) stops it; never `pkill -f desktop.ts`.
- The receiver log prints time in the local format, and the device is always `cli` because `client.ts` hardcodes it.
- The tray app (`desktop-tray`) reads `~/Library/Application Support/dev.narze.pood.desktop/config.json`, which the user's installed app shares. Pointing it at a verification gateway means editing the user's config, so do it only with explicit permission. Back the file up first and restore it afterwards. Even then the native menu has no automation handle, so evidence is limited to `bun run dev` stderr (state changes are logged) and `pbpaste`. Otherwise report `desktop-tray` as skipped.
- Paste mode types into the focused app. Never enable it during an agent run.
