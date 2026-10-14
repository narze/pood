// Streams a 16 kHz PCM16 mono file through the gateway in real time and prints partials/final.
// Usage: bun scripts/client.ts <file.pcm> [ws://127.0.0.1:8790/v1/ws] [token]
import { readFileSync } from "node:fs";

const [file, url = "ws://127.0.0.1:8790/v1/ws", token = process.env.POOD_TOKEN] = process.argv.slice(2);
if (!file) throw new Error("usage: bun scripts/client.ts <file.pcm> [url] [token]");
const pcm = readFileSync(file);
const ws = new WebSocket(`${url}?device=cli`, { headers: token ? { Authorization: `Bearer ${token}` } : {} } as any);

ws.onmessage = async (ev) => {
  const msg = JSON.parse(String(ev.data));
  if (msg.type === "partial") process.stdout.write(`\r\x1b[K… ${msg.text}${msg.stash}`);
  else console.log(`\n${JSON.stringify(msg)}`);
  if (msg.type === "ready") {
    ws.send(JSON.stringify({ type: "start", utteranceId: "cli" }));
    for (let i = 0; i < pcm.length; i += 3200) {
      ws.send(pcm.subarray(i, i + 3200));
      await Bun.sleep(100);
    }
    ws.send(JSON.stringify({ type: "stop", utteranceId: "cli" }));
  }
  if (msg.type === "final" || (msg.type === "error" && msg.code === "upstream")) ws.close();
};
ws.onclose = (ev) => ev.code !== 1000 && ev.code !== 1005 && console.error(`closed ${ev.code} ${ev.reason}`);
