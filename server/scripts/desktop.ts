// Desktop receiver: prints every transcript the same user speaks on the phone. Reconnects with backoff.
// Usage: bun scripts/desktop.ts [ws://127.0.0.1:8790/v1/desktop] [name] [token]
import { hostname } from "node:os";

const [url = "ws://127.0.0.1:8790/v1/desktop", name = hostname().split(".")[0]!, token = process.env.POOD_TOKEN] = process.argv.slice(2);
let backoff = 1000;

function connect() {
  const ws = new WebSocket(`${url}?name=${encodeURIComponent(name)}`, { headers: token ? { Authorization: `Bearer ${token}` } : {} } as any);
  ws.onmessage = (ev) => {
    const msg = JSON.parse(String(ev.data));
    if (msg.type === "hello") {
      backoff = 1000;
      console.error(`connected as ${msg.user} (${name})`);
    } else if (msg.type === "transcript") console.log(`[${new Date(msg.createdAt).toLocaleTimeString()} ${msg.device}] ${msg.text}`);
  };
  ws.onclose = (ev) => {
    console.error(`disconnected (${ev.code}), retrying in ${backoff / 1000}s`);
    setTimeout(connect, backoff);
    backoff = Math.min(backoff * 2, 30_000);
  };
}

connect();
