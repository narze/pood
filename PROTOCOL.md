# Pood protocol v1

This is how Pood clients (the Android app and desktop receivers) talk to the Pood gateway. Every path below is served by the gateway; the
client never talks to an ASR provider directly.

## Auth

Each request is authenticated in one of two ways:
1. **Tailscale identity.** `tailscale serve` adds a `Tailscale-User-Login` header to each request. The login must be
   listed in `auth.allowedUsers`. The gateway listens on loopback only, so this header can only come from `tailscale serve`.
2. **Bearer token** (fallback). Send `Authorization: Bearer <token>` or a `?token=<token>` query parameter. The token
   must be listed in `auth.tokens`.

If neither check passes, the gateway responds `401`.

## HTTP

| Method | Path | Response |
|---|---|---|
| GET | `/healthz` | `{ok: true}`. No auth needed. |
| GET | `/v1/me` | `{user, model}`. Use it for the app's "Test connection" button. |
| GET | `/v1/history?before=<id>&limit=<n≤200, default 50>` | `{items: Transcript[]}`, newest first. Only the caller's own transcripts are returned. |
| DELETE | `/v1/history/:id` | `204`, or `404` if no such transcript exists. |

`Transcript` fields:
- `id` (number)
- `createdAt` (epoch ms)
- `device` (string)
- `text` (string)
- `model` (string)
- `durationMs` (number)
- `latencyMs` (number)

## WebSocket: `/v1/ws?device=<name>`

The `device` parameter is a free-form label that gets stored with each transcript. Text frames are JSON; binary frames
are audio.

**Audio format:** PCM signed 16-bit little-endian, 16 kHz, mono. Send frames of about 100 ms (3200 bytes). Other sizes
work too, but must be a whole number of samples.

A connection carries at most one utterance at a time:
1. Send `start`.
2. Send the audio as binary frames.
3. Send `stop`.
4. Wait for `final` or `error`. Partial transcripts may arrive at any point while this is happening.

### Client → server
```jsonc
{"type": "start",  "utteranceId": "u1"}   // utteranceId: any string the client chooses
{"type": "stop",   "utteranceId": "u1"}   // end of speech → server produces the final transcript
{"type": "cancel", "utteranceId": "u1"}   // drop it; no final is sent and nothing is saved
```

### Server → client
```jsonc
{"type": "ready",   "user": "you@github", "model": "qwen3.8-omni-flash-realtime"}   // sent on connect
{"type": "partial", "utteranceId": "u1", "text": "confirmed", "stash": " still changing"}
{"type": "final",   "utteranceId": "u1", "id": 42, "text": "...", "durationMs": 3100, "latencyMs": 410, "delivered": ["MacBook"]}
{"type": "error",   "utteranceId": "u1", "code": "busy|bad_request|upstream|too_long", "message": "..."}
```

Notes:
- **Partials** are a live preview from a fast ASR pass and may differ from `final`. Render `text` normally and `stash`
  dimmed.
- **Short or silent recordings.** If there was under 300 ms of audio louder than -50 dBFS, or the transcript comes back empty, the server
  sends a `final` with `id: null` and `text: ""` and saves nothing.
- **Length limit.** An utterance longer than `maxUtteranceSeconds` is stopped automatically. The server sends
  `error` with code `too_long`, then a `final` for the audio up to that point.
- **Delivered.** `delivered` lists the names of the desktops the transcript was broadcast to (see below). It is empty
  when no desktop was connected or the final is empty.
- **Errors.** `error` codes `busy` and `bad_request` describe a single bad message; the utterance, if any, keeps going.
  The `upstream` code means the utterance failed and no `final` will follow.

## WebSocket: `/v1/desktop?name=<name>`

Desktop receivers keep this socket open to get every transcript their user speaks, from any phone. Auth is the same as
above; on the tailnet, a laptop logged in as the same Tailscale user is accepted without a token. `name` is shown back
to the phone in `final.delivered`.

The socket is receive-only; anything the desktop sends is ignored. The server pings idle sockets and drops dead ones.

### Server → desktop
```jsonc
{"type": "hello",      "user": "you@github"}                                                   // sent on connect
{"type": "transcript", "id": 42, "text": "...", "device": "pixel", "createdAt": 1760500000000, "fresh": true} // on every non-empty final
```

`fresh` is `true` only when the transcript is broadcast within 10 s of the phone's `stop`. Desktops in paste mode paste
fresh transcripts and only copy the rest. Anything re-sent from history is never fresh.

Delivery is live only: a desktop that is offline misses the transcript, which remains in history.
