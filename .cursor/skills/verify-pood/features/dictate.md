# Dictate

The user taps record on the phone bubble and speaks. Live partial text appears, and on stop a final transcript arrives, which the phone copies to the clipboard. The transcript is saved to the user's history with its duration and latency.

## Sub-features

- `dictate-ready`: connecting to `/v1/ws` yields `ready` with the user and model.
- `dictate-partial`: `partial` frames with `text` and `stash` stream while audio is flowing.
- `dictate-final`: `stop` yields `final` with a numeric `id`, the cleaned `text`, `durationMs` ≈ clip length, and `latencyMs`.
- `dictate-thai-mix`: Thai speech with English terms comes back in Thai script, with the English terms in Latin letters.
- `dictate-saved`: the final is persisted as one row for this user with `device = cli`.

## How to get to it (user POV)

- The Android bubble: tap it, tap record, speak, tap again.
- The Android persistent notification's **Record / stop** action.
- Both entry points use the same `/v1/ws` protocol. Through the gateway stand-in only the protocol is reachable. Report the two UI entry points as skipped (see `SKILL.md`, "Other surfaces").

## Driving it with pood-verify

Preconditions:

- `$V doctor` passes and the run's history is empty (`$V api GET /v1/history` → `{"items":[]}`), or you know the current max id.

- **English utterance.** Synthesize and speak. Run `$V clip dictate-en Samantha "Please deploy the new server and check the logs."` and then `$V speak dictate-en`. `speak-dictate-en.jsonl` shows `ready`, then several `… Please deploy…` partial lines, then `{"type":"final",…,"id":<n>,"text":"Please deploy the new server and check the logs.",…}` with `durationMs` ≈ the length that `clip` printed.
- **Thai/English mix.** Run `$V clip dictate-th Kanya "วันนี้ต้อง deploy server ใหม่ แล้วก็ต้องเช็ค log ด้วยนะ"` and then `$V speak dictate-th`. The final `text` is in Thai and contains `deploy`, `server` and `log` in Latin letters.
- **Persistence.** Run `$V api GET /v1/history` and `$V db "select id,user,device,text,duration_ms,latency_ms from transcripts order by id"`. Both show the two transcripts newest first (API) or in id order (DB), with `user = token:veri`, `device = cli`, and the same ids as the finals.

## Gotchas

- ASR is live and nondeterministic, so assert key words only. A partial line that ends in `.` is normal: the dimmed `stash` gets appended.
- `client.ts` always connects with `?device=cli`. To prove that a custom device label is stored, use a raw Bun script with `?device=<name>`.
- Clips of under about 0.3 s of voiced audio take the silence gate path instead (see `silence-and-limits.md`). Keep test phrases at least a few words long.
- A Thai clip from `say -v Kanya` is synthetic speech. A correct transcript proves the pipeline, not real-speaker accuracy.
