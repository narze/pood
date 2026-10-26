# pood-server

This is the gateway between the Pood Android app and Qwen-Omni realtime (DashScope). The wire protocol is described in
[`../PROTOCOL.md`](../PROTOCOL.md).

## Develop

```sh
bun install
cp pood.example.toml pood.toml                  # set auth.allowedUsers / tokens, qwen.realtimeUrl
echo 'DASHSCOPE_API_KEY=sk-...' > .env          # Bun loads .env automatically
bun run dev                                     # http://127.0.0.1:8790
bun test                                        # unit tests (fake provider)
LIVE=1 bun test                                 # also hits DashScope
bun scripts/client.ts some.pcm ws://127.0.0.1:8790/v1/ws <token>   # stream a 16 kHz PCM16 clip
bun scripts/desktop.ts ws://127.0.0.1:8790/v1/desktop MacBook <token>   # print transcripts as they are spoken
POOD_DUMP_AUDIO=/tmp/pood-dump bun run dev     # also save each utterance's raw audio (debugging)
```

**Silence gate.** If an utterance has less than 300 ms of audio louder than -50 dBFS, it never reaches the model, and
the client gets an empty `final`. This matters because Qwen-Omni makes up fluent sentences when it gets silence or
noise, and changing the prompt doesn't stop it.

## Deploy (Linux home server)

1. Build the binary for the server's architecture on your dev machine, then copy the folder to the server:
   ```sh
   bun run build:linux-x64                       # or build:linux-arm64
   scp -r dist/linux-x64 server:pood-install
   ```
2. On the server, run the install script, then fill in the settings:
   ```sh
   cd ~/pood-install && sudo ./install.sh
   sudo $EDITOR /etc/pood/env                    # DASHSCOPE_API_KEY=...
   sudo $EDITOR /etc/pood/pood.toml              # auth.allowedUsers, qwen.realtimeUrl
   sudo systemctl restart pood && journalctl -u pood -f
   sudo tailscale serve --bg --https=8790 http://127.0.0.1:8790
   ```
3. Check it from any device on the tailnet: `curl https://<server>.<tailnet>.ts.net:8790/v1/me`.

**Without sudo** (a systemd user service; needs `loginctl enable-linger` for your user, and `tailscale set
--operator=$USER` so you can run `tailscale serve`):
```sh
scp dist/linux-x64/pood-server server:.local/bin/
scp deploy/pood.user.service server:.config/systemd/user/pood.service
# on the server: put the config in ~/.config/pood/pood.toml (dbPath under ~/.local/share/pood) and secrets in ~/.config/pood/env
systemctl --user daemon-reload && systemctl --user enable --now pood && journalctl --user -u pood -f
tailscale serve --bg --https=8790 http://127.0.0.1:8790
```
To update, copy the new binary over and run `systemctl --user restart pood`.

Notes:
- **Where things live.** History is stored in `/var/lib/pood/pood.db`. The service runs as a sandboxed dynamic user.
- **Updating.** Rebuild, copy the new `pood-server` binary over, and run `sudo ./install.sh` again. The script doesn't
  touch an existing config.
