# History

The phone's main screen lists the user's past transcripts, newest first, with **Load more**, a tap to copy, and **Delete**. The gateway returns only the caller's own transcripts.

## Sub-features

- `history-list`: `GET /v1/history` returns `{items}`, newest first, with `id, createdAt, device, text, model, durationMs, latencyMs`.
- `history-page`: `limit` (1–200, default 50) and `before=<id>` page backwards.
- `history-delete`: `DELETE /v1/history/:id` returns `204` and the item disappears. A repeat or unknown id returns `404`.
- `history-scoped`: another user's ids are invisible and can't be deleted (`404`).

## How to get to it (user POV)

- The Android main screen's **History** section, with **Refresh**, **Load more** and **Delete** on each item. It is not reachable against a local verification gateway (see `SKILL.md`), so report it as skipped.
- The gateway HTTP endpoints, which that screen calls.

## Driving it with pood-verify

Preconditions:

- At least three transcripts exist. Create them with `dictate.md`, or run `$V clip h1 Samantha "First note."`, `$V clip h2 Samantha "Second note."`, `$V clip h3 Samantha "Third note."` and `$V speak h1`, `h2`, `h3`. Record the three final ids.

- **List.** `$V api GET /v1/history` returns `HTTP 200` with the items in descending `id` order.
- **Page.** Run `$V api GET "/v1/history?limit=2"`, which returns the two newest. Then run `$V api GET "/v1/history?limit=2&before=<second id>"`, which starts strictly below that id.
- **Clamp.** `$V api GET "/v1/history?limit=999"` returns at most 200 items. `limit=0` behaves as the default of 50.
- **Delete.** `$V api DELETE /v1/history/<middle id>` returns `HTTP 204`. Then `$V api GET /v1/history` no longer lists it, and `$V db "select count(*) from transcripts where id=<middle id>"` returns `0`.
- **Delete again.** `$V api DELETE /v1/history/<middle id>` returns `HTTP 404`.

## Gotchas

- `api` builds the URL as `$BASE$path`, so quote paths that contain `&`.
- `history-scoped` needs a second identity. See `auth.md` for the Tailscale header path, and report it as unverified if skipped.
- A `final` with `id: null` (silence) never appears here. That is expected, not a bug.
