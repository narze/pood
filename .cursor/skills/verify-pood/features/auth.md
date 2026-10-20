# Auth

Only the owner's devices can use the gateway. Behind `tailscale serve`, the Tailscale login identifies the user. Other clients use a bearer token, sent as a header or as `?token=`. Anything else gets `401`, except the open health check. The phone's **Save & test** button shows "Connected as <user>".

## Sub-features

- `auth-token-header`: `Authorization: Bearer <token>` → user `token:<first 4 chars>`.
- `auth-token-query`: `?token=<token>` works the same way, on HTTP and on WebSocket upgrades.
- `auth-tailscale`: a `Tailscale-User-Login` header listed in `auth.allowedUsers` → that login as the user.
- `auth-reject`: no credentials, a wrong token or an unlisted login → `401 unauthorized`, on `/v1/*` and on the WebSocket paths.
- `auth-health-open`: `/healthz` → `{ok:true}` with no auth.
- `auth-me`: `GET /v1/me` → `{user, model}`, which backs **Save & test**.

## How to get to it (user POV)

- The Android settings: enter the gateway URL and tap **Save & test**. This only works through Tailscale; report it as skipped against a local run.
- `curl https://<server>.<tailnet>.ts.net:8790/v1/me` from any tailnet device, as in the server README.

## Driving it with pood-verify

Preconditions:

- `$V doctor` passes. Load the run's state with `source "$TMPDIR/pood-verify/$POOD_VERIFY_RUN/state.env"` to get `BASE` and `TOKEN`.

- **Me.** `$V api GET /v1/me` returns `{"user":"token:veri","model":"qwen3.8-omni-flash-realtime"}` and `HTTP 200`.
- **Query token.** `curl -s "$BASE/v1/me?token=$TOKEN"` returns the same body.
- **Reject.** `curl -s -o /dev/null -w '%{http_code}' $BASE/v1/me`, then the same with `-H 'Authorization: Bearer wrong'`, and then with `$BASE/v1/ws` and `$BASE/v1/desktop`. Each prints `401`.
- **Health open.** `curl -s $BASE/healthz` returns `{"ok":true}`.
- **Tailscale identity.** Before launching, add `allowedUsers = ["verify@github"]` to the run's toml. Or edit it, then `cleanup` and `launch`. Then `curl -s -H 'Tailscale-User-Login: verify@github' $BASE/v1/me` returns `"user":"verify@github"`, and `-H 'Tailscale-User-Login: stranger@github'` returns `401`. That second identity is also what `history-scoped` and `desktop-scoped` need.

## Gotchas

- `launch` writes `allowedUsers = []`, so the Tailscale path is rejected until you add a login.
- The gateway trusts the `Tailscale-User-Login` header only because it binds to loopback behind `tailscale serve`. Sending it with curl locally imitates that proxy. It is not proof that `tailscale serve` is configured correctly on the home server, which stays unverified.
- Save the curl outputs into the evidence dir (`| tee -a "$EVID/auth.log"`). Plain curl does not write to `api.log`.
