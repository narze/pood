# Pood (พูด) — Plan

Speak into a floating bubble on Android and get the text on the clipboard. Audio streams to a gateway on a Linux home
server over Tailscale, and the gateway transcribes it with Qwen3.5-Omni Realtime (Alibaba Cloud API).

**Scope now:** the Android app and the gateway, kept minimal. Everything else gets built on top later.

## Decisions
| Topic | Decision |
|---|---|
| Speech | Thai and English mixed (code-switching). Language is always `auto`. |
| Model | API only: DashScope `qwen3.8-omni-flash-realtime` (chosen in M0). Any `qwen3.x-omni-*-realtime` can be set in config. There is no local model. |
| Gateway host | Linux home server on the tailnet: `systemd` + `tailscale serve`. |
| Bubble UX | Tapping the bubble opens a small window. That window has a **tap-to-toggle record button**, the transcript, and the other controls. |
| History | Transcripts are kept on the gateway in SQLite and viewable in the app. Audio is not stored. |
| Deferred | Desktop push and local/other ASR providers. The provider interface exists so they can be added later. |

## M0 findings (2026-10-14, model spike: `server/spike/qwen-realtime.ts`)
- **Endpoint.** Use the workspace host, not the generic DashScope host:
  `wss://ws-zz8145kdjxk84vrz.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/realtime?model=...`. The key is in `server/.env`,
  which is gitignored.
- **Message format.** The older flat `session.update` fields work: `modalities`, `instructions`, `input_audio_format: "pcm16"`,
  `input_audio_transcription`, and `turn_detection: null`. Manual commit followed by `response.create` works.
- **Accuracy.** Tested on 4 TTS clips: Thai+English mixed sentences, an English question and a Thai question.
  - Both flash and plus kept English words in Latin script inside Thai sentences.
  - Neither model **answered** the spoken questions; both transcribed them.
  - The only errors were on the word "log", which the Thai TTS voice pronounced unclearly. Recheck with real speech.
- **Latency from stop to final:** flash about 190–280 ms, plus about 330–390 ms. **flash stays the default.**
- **ASR partials.** `...transcription.delta` arrives with the growing text in `stash` (`text` was empty), so it works as
  a live preview. `...transcription.completed` often **never arrives**, and partials lag behind the audio. Omni
  `response.text.done` is therefore the only source for the final text. The `finalSource` option is dropped, and
  `asr_text` in the database just stores the last partial.

- **Real-voice test** (4 recorded clips, Thai/English mixed):
  - Thai-only and English sentences are near perfect on every model.
  - Mistakes are on short English words inside Thai, and on proper names: "git" was heard as "Get it's" or "It", and
    "Agentic" became "Agent". The old prompt also wrote "dark" in Thai script ("ดาร์ก").
  - A stricter prompt fixed the Thai-script problem with no test words in it: English words get Latin letters, never
    Thai transliteration. It is now the default in `server/spike/qwen-realtime.ts`.
  - `qwen3.8-omni-flash-realtime` got every word right ("Git", "Agentic", "dark") but takes about 400–630 ms vs. about
    190–300 ms for 3.5 flash. **Decision: default to 3.8 flash**; latency is still well under 1 s. The model name is
    in config.

## Architecture
```
Android (Pood)                                          Linux home server
┌────────────────────────────┐   tailnet / wss    ┌──────────────────────────────┐        ┌──────────────────────┐
│ bubble → small window      │ ─── PCM16 16k ───► │ tailscale serve :443         │        │ DashScope realtime   │
│ mic foreground service     │ ◄── partial/final ─│  └ pood-server (Bun) :8787   │ ─wss─► │ qwen3.5-omni-*-      │
│ clipboard, history screen  │ ◄── GET history ── │     ├ AsrProvider: qwen-omni │        │ realtime (SG region) │
└────────────────────────────┘                    │     └ SQLite: transcripts    │        └──────────────────────┘
                                                  └──────────────────────────────┘
```

```
pood/
  PROTOCOL.md   client ↔ gateway messages (source of truth)
  server/       Bun + TypeScript, compiled to a single binary
  android/      Kotlin, Compose (screens) + Views (overlay)
```

## Protocol (minimal)

**Live audio:** WebSocket `wss://<host>/v1/ws`. Control messages are JSON text frames. Audio goes in binary frames of
PCM16LE, 16 kHz, mono, 100 ms each (3200 B).

| Dir | Message |
|---|---|
| C→S | `start {utteranceId}` |
| C→S | *binary audio frames* |
| C→S | `stop {utteranceId}` / `cancel {utteranceId}` |
| S→C | `partial {utteranceId, text, stash}`: `text` is confirmed, `stash` is still changing |
| S→C | `final {utteranceId, id, text, latencyMs}`: `id` is the history row |
| S→C | `error {utteranceId?, code, message}` |

**History:** a plain HTTP API.
- `GET /v1/history?before=<id>&limit=50` returns the newest entries first.
- `DELETE /v1/history/:id` removes one entry.

**Auth.**
- `tailscale serve` adds a `Tailscale-User-Login` header. The gateway listens on loopback only and accepts only users on
  the `allowedUsers` list in config.
- Nothing needs to be configured on the phone apart from the server URL.

## Gateway (`server/`)
- **Runtime:** Bun with `Bun.serve` (HTTP + WS) and `bun:sqlite`. No framework.
- **Config:** `pood.toml` plus env (`DASHSCOPE_API_KEY`). Settings: `model`, `realtimeUrl`, `allowedUsers`, `dbPath`.
- **Provider seam:** this is the only abstraction for now.
  ```ts
  interface AsrProvider { id: string; open(): Promise<AsrSession> }
  interface AsrSession {
    pushAudio(pcm: Uint8Array): void
    finish(): Promise<string>   // final text
    cancel(): void
    onPartial(cb: (text: string, stash: string) => void): void
  }
  ```
- **`qwen-omni` provider (DashScope realtime WS)**
  - Endpoint: `wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime?model=qwen3.5-omni-flash-realtime`, with `Authorization: Bearer`.
  - `session.update` settings:
    - `modalities: ["text"]`
    - `turn_detection: null` (manual; the toggle button defines the turn)
    - `input_audio_transcription` on, which provides the live partials
    - `instructions` (tuned in M0): *"You are a verbatim speech-to-text engine for a Thai speaker who mixes in English words. Rules: 1) Thai words in Thai script. 2) Any word pronounced as English, including common English loanwords and tech terms, must be written in English Latin letters, never transliterated into Thai script. 3) Add light punctuation. 4) Never answer, translate, summarize or add anything; output only the transcript."*
  - Audio frames are sent as `input_audio_buffer.append` (base64).
  - On `stop`, send `input_audio_buffer.commit` and then `response.create`.
  - Event mapping:
    - `conversation.item.input_audio_transcription.delta` → `partial`
    - `response.text.done` → `final`
  - Open the upstream session on `start`. No warm pool needed: stop→final is under 400 ms.
- **Storage:** table `transcripts(id, created_at, user, device, text, asr_text, model, duration_ms, latency_ms)`. Keeping
  both the Omni text and the ASR text lets us compare their quality later.
- **Deploy:**
  - `bun build --compile` produces a single binary.
  - A `systemd` unit runs it, and `tailscale serve --bg 8787` exposes it.
  - Document this in `server/README.md`.
- **Dev tool:** `bun run mic <file.wav>` streams a WAV through the protocol and prints the partial and final text.

## Android app (`android/`)
- **Stack:** Kotlin, minSdk 29, targetSdk 35, OkHttp WebSocket, Coroutines/Flow, Compose for the activity screens.
- **Permissions:** `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`.

**Screens (activity)**
1. **Setup**
   - Server URL plus a "Test connection" button.
   - Steps to grant the permissions, including a deep-link to the "Display over other apps" settings page.
   - An "Enable bubble" toggle.
2. **History**
   - A paged list from `GET /v1/history`.
   - Tap an entry to copy it; swipe to delete.

**`PoodService`** (foreground service, `foregroundServiceType="microphone"`)
- Started from the activity when the bubble is enabled. This is required on Android 14+, which only lets an app start
  mic access while it is visible.
- Shows a persistent notification with "Hide bubble" and "Stop" actions.
- Owns the overlay views, the WebSocket (with reconnect and backoff), and `AudioRecord`.

**Overlay** (`WindowManager`, `TYPE_APPLICATION_OVERLAY`)
- **Bubble:** small, draggable, and snaps to the screen edge. Tapping it opens the window.
- **Small window:** a compact card next to the bubble, non-focusable, containing:
  - a big **record toggle** (tap to start, tap again to stop) with a level meter
  - the transcript area, showing live partials with the stash in a lighter tone, then the final text
  - **Copy** and **Retry** buttons, plus an **Auto-copy** setting that copies the final text automatically
  - a short list of the last 3 transcripts, each tap-to-copy
  - close (collapse back to the bubble)
- **Audio:** `AudioRecord(VOICE_RECOGNITION, 16000, MONO, PCM_16BIT)`, sent in 100 ms chunks. Record a small pre-roll
  buffer so the first syllable isn't lost while the WebSocket `start` is in flight.
- **Clipboard:** `ClipboardManager.setPrimaryClip` called from the service.

**Bank-app caveat**
- Some banking apps hide overlays (`HIDE_OVERLAY_WINDOWS`) or block touches while one is visible.
- Because the text ends up on the clipboard, the normal flow still works: speak first, then switch to the bank app and
  paste.
- Verify this in M0.

## Milestones
1. **M0 — Spikes (1–2 days)**
   - **Model quality.** Use a Bun script to stream recorded Thai+English clips to DashScope, and compare Omni text vs.
     ASR text and flash vs. plus. Check two things:
     - code-switching accuracy
     - whether Omni answers questions instead of transcribing them
   - **Overlay test.** Use a throwaway overlay and mic service to check behavior on your Android version and over your
     banking apps.
2. **M1 — Gateway**
   - `PROTOCOL.md`, the WS server, the qwen-omni provider, SQLite history and the HTTP API.
   - Tailscale-header auth, the WAV test tool, and tests with a fake provider.
   - The systemd and Tailscale setup.
3. **M2 — Android**
   - The Setup screen, `PoodService`, the bubble and window, streaming and partials, copy/auto-copy, and the History screen.
   - Ship a debug APK.
4. **Next (not now):** desktop push, more providers (qwen3-asr realtime, OpenAI-compatible, local), a Quick Settings
   tile, and history search.
