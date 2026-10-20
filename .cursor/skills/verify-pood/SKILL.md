---
name: verify-pood
description: Launch a disposable Pood gateway (server/, Bun + live Qwen-Omni realtime) and drive it the way the Android phone and the desktop receivers do, over the real /v1/ws, /v1/desktop and /v1/history protocol, capturing transcripts, delivery logs, HTTP bodies and DB readbacks as evidence. Use it to prove that a gateway or protocol change works end to end: dictation, delivery to desktops, history, the silence gate, limits and auth. It also covers the limits of driving the Android app and the Tauri menu bar app.
---

# Verify Pood

Pood is speech-to-clipboard. A phone (the Android bubble in `android/`) streams PCM audio to the **gateway** (`server/`). The gateway runs it through Qwen-Omni realtime (DashScope), saves the transcript to SQLite, sends the `final` back to the phone, and broadcasts it to every connected **desktop receiver** of the same user (the Tauri tray app in `desktop/`). The wire contract is `PROTOCOL.md`.

**The surface you verify is the gateway protocol.** Both apps are thin clients of it, and the repo already ships stand-ins for them: `server/scripts/client.ts` plays the phone and `server/scripts/desktop.ts` plays a desktop. Everything goes through `bin/pood-verify`, which wraps those scripts, curl and sqlite3 against one isolated instance.

Other surfaces, and why they are not driven by default:
- **Android app.** It has no token field and appends paths straight onto the saved URL, so it can only reach a gateway through `tailscale serve`, not a local verification instance. The emulator (`adb devices`, for example `emulator-5580`) holds the user's real settings; never overwrite them. Audio input can't be scripted either.
- **Desktop tray app.** Its config is fixed at `~/Library/Application Support/dev.narze.pood.desktop/config.json`, the same file the user's installed app reads, so running `bun run dev` in `desktop/` against a verification gateway would rewrite their config. Its menu is native NSMenu with no automation handle. Only drive it when the user explicitly allows it (see `features/desktop-delivery.md`).

## Isolation and safety

- The user usually has their own gateway running at `127.0.0.1:8790`, with `server/pood.toml` and `server/pood.db`. **Never drive, restart or kill it**, and never read or write `server/pood.db`.
- `launch` picks a free random port, writes its own `pood.toml` with a fresh random bearer token, and puts the DB in a scratch dir. Any number of runs can live side by side; each is addressed by `POOD_VERIFY_RUN`.
- The provider is the **live** DashScope API, using `DASHSCOPE_API_KEY` from `server/.env`, which Bun loads automatically because the gateway runs from `server/`. That is the production path. There is no fake provider outside `bun test`, so each voiced utterance is a real, cheap API call. Silent and too-short clips never reach the provider.

## Launch

From the repo root:

```sh
V=.cursor/skills/verify-pood/bin/pood-verify
eval "$($V launch | tee /dev/stderr | grep ^export)"     # sets POOD_VERIFY_RUN
```

Ready means `launch` printed `ready: http://127.0.0.1:<port>  pid <pid>  evidence <dir>`. It only prints that after it has seen the log line `pood-server listening on http://127.0.0.1:<port>` and after an authenticated `GET /v1/me` succeeds. The SQLite schema is created on open, so an empty data dir is fully usable; there is no seed data. If `launch` dies, it prints `server.log`. The usual causes are a missing `DASHSCOPE_API_KEY` or no `node_modules` (launch runs `bun install` itself the first time).

Requirements: `bun`, `curl`, `jq`, `sqlite3`, `lsof`, `openssl`, `ffmpeg`, `timeout` (Homebrew coreutils) and macOS `say`. All of them are present on this Mac.

## Doctor

```sh
$V doctor
```

This is read-only. It checks that our pid is alive and owns the port, that `/healthz` returns `{ok:true}`, that `/v1/me` with our token answers `user == "token:veri"`, that unauthenticated `/v1/me` gets a `401`, and that the scratch DB exists. It also prints the git revision the gateway was started from next to the current HEAD. **If they differ, `cleanup` and `launch` again**: the gateway does not hot-reload (`launch` uses `bun src/main.ts`, not `--watch`). Run doctor first whenever anything looks off.

## Drive

| Command | What it does, as which user surface |
|---|---|
| `$V clip <name> <voice> "<text>"` | Synthesizes speech with `say -v <voice>` and converts it to 16 kHz mono PCM16, writing `evidence/<name>.pcm`. Use `Samantha` (en_US) or `Kanya` (th_TH) for Thai/English code-switching. |
| `$V silence <name> <seconds>` | Writes a silent clip to `evidence/<name>.pcm`. |
| `$V speak <name>` | **Phone.** Streams the clip over `/v1/ws` in real time (`start` → 3200-byte frames every 100 ms → `stop`) and waits for `final`/`error`. Writes `evidence/speak-<name>.jsonl`. The device label is always `cli`. |
| `$V listen <name>` | **Desktop.** Starts a background receiver on `/v1/desktop?name=<name>` and waits for `connected as …`. Each transcript is appended to `evidence/desktop-<name>.log` as `[time cli] text`. |
| `$V api <METHOD> <path>` | **Phone HTTP** (for example the "Test connection" button and the history screen). Makes an authenticated curl call and prints the body and `HTTP <code>`, appending both to `evidence/api.log`. |
| `$V db "<sql>"` | Read-only SQL against the run's DB (table `transcripts`: `id, created_at, user, device, text, asr_text, model, duration_ms, latency_ms`). Output is appended to `evidence/db.log`. |

For protocol cases the helpers don't cover (cancel, busy, malformed frames, a second user), write a tiny Bun WebSocket script in the run's scratch dir and send the exact frames from `PROTOCOL.md`. `source "$TMPDIR/pood-verify/$POOD_VERIFY_RUN/state.env"` gives you `BASE`, `PORT`, `TOKEN` and `EVID` (the evidence dir). Recipes are in `features/`.

## Evidence

Evidence for a run goes to `artifacts/verify-pood/<run>/` in the repo. The `artifacts/` directory is gitignored. It holds `server.log`, `revision.txt`, the clips and `clips.tsv` (name, voice, spoken text), `speak-*.jsonl`, `desktop-*.log`, `api.log` and `db.log`. At cleanup it also gets a `.backup` copy of the run's `pood.db`.

Proof standards:
- **Drive the real path.** Audio goes over `/v1/ws` and transcripts arrive on `/v1/desktop`; never call `History.add` or other internals. The live provider is the real boundary, so don't mock it.
- **Capture the action and the result.** The `speak-*.jsonl` file holds the frames the "phone" received (partials, then `final` with `id`, `text`, `delivered`).
- **Verify side effects separately from what the phone saw.** A `final` with `delivered: ["X"]` is half the proof. The other half is the line in `desktop-X.log`, plus a row in `GET /v1/history` and in `db`. A `final` with `id: null` must leave no row behind.
- ASR output varies. Assert that key words are present, not exact punctuation. Thai clips may come back with English terms in Latin script; that is by design.

## Cleanup

```sh
$V cleanup
```

This kills only the pids this run recorded (the gateway and every `listen` receiver), backs up the DB into the evidence dir, and deletes `$TMPDIR/pood-verify/<run>/`. It prints the evidence dir, which survives. Run it after every attempt, failed ones included. Never `pkill bun`: the user's gateway is a `bun src/main.ts` too. If a run's state dir is gone but a process lingers, find it with `lsof -nP -iTCP:<port> -sTCP:LISTEN` and kill only that pid. To delete old evidence, run `rm -rf artifacts/verify-pood/<run>`, and only when the user asks.

## Feature map

`features/README.md` indexes one recipe per user-facing feature. Read it before driving. When a recipe can't be satisfied, report it as failed or skipped with the reason; don't weaken it. Keep the map current with `/maintain-verification-skill`.
