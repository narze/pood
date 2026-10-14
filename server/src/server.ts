import type { ServerWebSocket } from "bun";
import { BYTES_PER_MS, VOICE_DBFS, dbfs } from "./audio";
import { authenticate } from "./auth";
import { cleanTranscript } from "./cleanup";
import type { Config } from "./config";
import type { History } from "./history";
import type { AsrProvider, AsrSession } from "./provider";

/** Less voiced audio than this is treated as "nothing said" and never sent to the model. */
const MIN_VOICED_MS = 300;

interface Utterance {
  id: string;
  session: AsrSession;
  bytes: number;
  voicedMs: number;
  /** Raw audio, kept only when POOD_DUMP_AUDIO is set (debugging). */
  dump?: Buffer[];
  stoppedAt?: number;
}

interface Conn {
  user: string;
  device: string;
  utterance?: Utterance;
}

type Socket = ServerWebSocket<Conn>;

export interface Deps {
  config: Pick<Config, "hostname" | "port" | "auth" | "maxUtteranceSeconds">;
  provider: AsrProvider;
  history: History;
  log?: (msg: string) => void;
}

export function createServer({ config, provider, history, log = () => {} }: Deps) {
  const send = (ws: Socket, msg: object) => ws.send(JSON.stringify(msg));
  const maxBytes = config.maxUtteranceSeconds * 1000 * BYTES_PER_MS;

  function start(ws: Socket, id: string) {
    if (ws.data.utterance) return send(ws, { type: "error", utteranceId: id, code: "busy", message: "an utterance is already active" });
    const utterance: Utterance = { id, session: provider.open(), bytes: 0, voicedMs: 0, dump: process.env.POOD_DUMP_AUDIO ? [] : undefined };
    ws.data.utterance = utterance;
    utterance.session.onPartial = (text, stash) => send(ws, { type: "partial", utteranceId: id, text, stash });
    utterance.session.result.then(
      (result) => {
        if (ws.data.utterance !== utterance) return;
        ws.data.utterance = undefined;
        const text = cleanTranscript(result.text);
        const durationMs = Math.round(utterance.bytes / BYTES_PER_MS);
        const latencyMs = Math.round(performance.now() - (utterance.stoppedAt ?? performance.now()));
        const saved = text
          ? history.add({ user: ws.data.user, device: ws.data.device, text, asrText: result.asrText ?? "", model: provider.model, durationMs, latencyMs })
          : null;
        log(`final ${ws.data.user} ${durationMs}ms audio, ${latencyMs}ms latency`);
        send(ws, { type: "final", utteranceId: id, id: saved?.id ?? null, text, durationMs, latencyMs });
      },
      (err: Error) => {
        if (ws.data.utterance !== utterance) return;
        ws.data.utterance = undefined;
        log(`upstream error ${ws.data.user}: ${err.message}`);
        send(ws, { type: "error", utteranceId: id, code: "upstream", message: err.message });
      },
    );
  }

  function stop(ws: Socket, utterance: Utterance) {
    if (utterance.stoppedAt !== undefined) return;
    utterance.stoppedAt = performance.now();
    if (utterance.dump) Bun.write(`${process.env.POOD_DUMP_AUDIO}/${Date.now()}-${utterance.id}.pcm`, Buffer.concat(utterance.dump));
    if (utterance.voicedMs < MIN_VOICED_MS) {
      // No speech: answer immediately without asking the provider, which would hallucinate.
      ws.data.utterance = undefined;
      utterance.session.cancel();
      send(ws, { type: "final", utteranceId: utterance.id, id: null, text: "", durationMs: Math.round(utterance.bytes / BYTES_PER_MS), latencyMs: 0 });
      return;
    }
    utterance.session.stop();
  }

  function cancel(ws: Socket) {
    const utterance = ws.data.utterance;
    if (!utterance) return;
    ws.data.utterance = undefined;
    utterance.session.cancel();
  }

  function onAudio(ws: Socket, pcm: Buffer) {
    const utterance = ws.data.utterance;
    if (!utterance || utterance.stoppedAt !== undefined) return; // late frames after stop are dropped
    if (pcm.length % 2) return send(ws, { type: "error", utteranceId: utterance.id, code: "bad_request", message: "audio frame is not whole PCM16 samples" });
    utterance.bytes += pcm.length;
    if (dbfs(pcm) > VOICE_DBFS) utterance.voicedMs += pcm.length / BYTES_PER_MS;
    utterance.dump?.push(Buffer.from(pcm));
    utterance.session.pushAudio(pcm);
    if (utterance.bytes >= maxBytes) {
      send(ws, { type: "error", utteranceId: utterance.id, code: "too_long", message: `stopped after ${config.maxUtteranceSeconds}s` });
      stop(ws, utterance);
    }
  }

  function onControl(ws: Socket, raw: string) {
    let msg: any;
    try {
      msg = JSON.parse(raw);
    } catch {
      return send(ws, { type: "error", code: "bad_request", message: "invalid JSON" });
    }
    const id = typeof msg?.utteranceId === "string" ? msg.utteranceId : undefined;
    if (!id) return send(ws, { type: "error", code: "bad_request", message: "utteranceId is required" });
    const current = ws.data.utterance;
    switch (msg.type) {
      case "start":
        return start(ws, id);
      case "stop":
        if (current && current.id === id) return stop(ws, current);
        break;
      case "cancel":
        if (current?.id === id) return cancel(ws);
        break;
      default:
        return send(ws, { type: "error", utteranceId: id, code: "bad_request", message: `unknown type ${msg.type}` });
    }
    send(ws, { type: "error", utteranceId: id, code: "bad_request", message: "no such active utterance" });
  }

  return Bun.serve<Conn>({
    hostname: config.hostname,
    port: config.port,
    fetch(req, server) {
      const url = new URL(req.url);
      if (url.pathname === "/healthz") return Response.json({ ok: true });

      const user = authenticate(req, config.auth);
      if (!user) return new Response("unauthorized", { status: 401 });

      if (url.pathname === "/v1/ws") {
        const device = (url.searchParams.get("device") ?? "unknown").slice(0, 64);
        return server.upgrade(req, { data: { user, device } }) ? undefined : new Response("expected websocket", { status: 400 });
      }
      if (url.pathname === "/v1/me" && req.method === "GET") return Response.json({ user, model: provider.model });
      if (url.pathname === "/v1/history" && req.method === "GET") {
        const before = Number(url.searchParams.get("before")) || undefined;
        const limit = Math.min(Math.max(Number(url.searchParams.get("limit")) || 50, 1), 200);
        return Response.json({ items: history.list(user, { before, limit }) });
      }
      const del = url.pathname.match(/^\/v1\/history\/(\d+)$/);
      if (del && req.method === "DELETE")
        return new Response(null, { status: history.delete(user, Number(del[1])) ? 204 : 404 });

      return new Response("not found", { status: 404 });
    },
    websocket: {
      open(ws) {
        log(`connect ${ws.data.user} (${ws.data.device})`);
        send(ws, { type: "ready", user: ws.data.user, model: provider.model });
      },
      message(ws, msg) {
        if (typeof msg === "string") onControl(ws, msg);
        else onAudio(ws, msg);
      },
      close(ws) {
        cancel(ws);
      },
    },
  });
}
