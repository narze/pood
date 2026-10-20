# Silence gate and utterance limits

When the user records nothing (or noise), the phone gets an empty final straight away and nothing is saved, so the model never hallucinates a sentence. Overlong recordings are cut off. A cancelled recording leaves no trace, and malformed messages get a clear error without killing the utterance.

## Sub-features

- `silence-empty`: under 300 ms of audio louder than -50 dBFS → `final` with `id: null`, `text: ""`, `latencyMs: 0` and `delivered: []`, and no history row.
- `limit-too-long`: past `maxUtteranceSeconds` → `error` with code `too_long`, then a `final` for the audio up to that point.
- `cancel`: `cancel` drops the utterance. No `final` arrives and nothing is saved.
- `busy`: a second `start` while one is active → `error` with code `busy`, and the first utterance continues.
- `bad-request`: invalid JSON, a missing `utteranceId`, an unknown `type`, `stop` for the wrong id, or an odd-length audio frame → `error` with code `bad_request`.

## How to get to it (user POV)

- On the phone: tap record and stay silent, or keep talking past the limit, or start and dismiss. Malformed frames only come from buggy clients, so drive them through raw protocol frames.

## Driving it with pood-verify

Preconditions:

- `$V doctor` passes. Note `$V db "select count(*) n from transcripts"` before you start.

- **Silence.** Run `$V silence quiet 2` and `$V speak quiet`. The final is `{"type":"final","utteranceId":"cli","id":null,"text":"","durationMs":2000,"latencyMs":0,"delivered":[]}`, and the DB count is unchanged. With a `$V listen` receiver running, its log gains nothing.
- **Too long.** Edit `maxUtteranceSeconds = 2` in `$TMPDIR/pood-verify/$POOD_VERIFY_RUN/pood.toml`, then `$V cleanup` and `$V launch` (the config is read at startup; this is a new run). Then run `$V clip long Samantha "This sentence is deliberately long so that it keeps going well past the two second limit we configured."` and `$V speak long`. The jsonl shows `{"type":"error",…,"code":"too_long","message":"stopped after 2s"}`, followed by a `final` whose `durationMs` ≈ 2000 and whose text is a prefix of the sentence.
- **Cancel, busy, bad request.** Write `$TMPDIR/pood-verify/$POOD_VERIFY_RUN/frames.ts`. It opens `ws://127.0.0.1:$PORT/v1/ws?device=frames` with `Authorization: Bearer $TOKEN`. After `ready`, it sends `start u1`, then `start u2` (expect `busy`), then `"not json"` (expect `bad_request` "invalid JSON"), then `{"type":"stop"}` (expect "utteranceId is required"), then a 3-byte binary frame (expect "audio frame is not whole PCM16 samples"), then `cancel u1`. It waits 2 s and prints every frame it received. Run it with `bun <file> | tee "$EVID/frames.jsonl"`. Expect no `final` for `u1`, and the DB count unchanged.

## Gotchas

- `say` output contains real silence between words, but a full sentence is well above the 300 ms voiced threshold. Use `$V silence` for the gate, not a whispered `say`.
- After a `too_long` recipe, relaunch with the default config before driving other features, or the 2 s limit will truncate them.
- `stop` for an id that isn't active returns `bad_request` "no such active utterance". That is the expected response to a stale stop, not a failure.
