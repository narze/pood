# Pood verification map

This directory is the maintained source for verifying Pood's user-facing behavior through the gateway protocol. Read this index before driving, then use the matching feature file as the recipe.

## Baseline preconditions

- A run launched with `pood-verify launch`, with `POOD_VERIFY_RUN` exported and `pood-verify doctor` passing at the current HEAD.
- The run's DB starts empty. Every recipe creates the transcripts it needs.
- Never drive the user's gateway at `127.0.0.1:8790`, their `server/pood.db`, their installed desktop app or the emulator's Pood settings.

## Driving conventions

- `V=.cursor/skills/verify-pood/bin/pood-verify`. Run every command from the repo root.
- Phone actions go through `$V speak`, desktop receivers through `$V listen`, and history and settings HTTP through `$V api`.
- For raw protocol frames, write a short Bun script under `$TMPDIR/pood-verify/$POOD_VERIFY_RUN/`. Read `PORT` and `TOKEN` from `state.env` there and save its stdout into the evidence dir.
- Assert transcript content by key words, never exact punctuation; the ASR is live.

## Proof and skip reporting

- Phone proof: the `speak-*.jsonl` frames, ending in `final` or `error`.
- Delivery proof: the matching line in `desktop-*.log` and the name in `final.delivered`.
- Persistence proof: a second view of the stored value, in `GET /v1/history` and in `$V db`.
- Record the feature ID with each artifact (name clips after it, e.g. `dictate-en`).
- An entry point you couldn't reach (the Android UI, the tray app) is reported as skipped, with the reason. It is never counted as verified through the protocol stand-in.

## Feature entry contract

Each feature file has an H1, one paragraph describing the behavior, and then four H2s in this order: `Sub-features`, `How to get to it (user POV)`, `Driving it with pood-verify`, `Gotchas`.

## Features

- [Dictate](./dictate.md): speak on the phone and get partials, a final transcript and a saved history row.
- [Desktop delivery](./desktop-delivery.md): each final is broadcast to every desktop of the same user, and the phone is told which ones got it.
- [History](./history.md): list, paginate and delete your own transcripts.
- [Silence gate and utterance limits](./silence-and-limits.md): empty finals for silence, `too_long`, `cancel`, `busy` and `bad_request`.
- [Auth](./auth.md): bearer token or Tailscale identity, `401` otherwise, `/healthz` open.
