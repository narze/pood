import { afterEach, expect, test } from "bun:test";
import { History } from "../src/history";
import type { AsrProvider, AsrResult, AsrSession } from "../src/provider";
import { createServer } from "../src/server";

/** Fake provider: emits a partial per audio frame; `stop()` resolves with what the test scripted. */
class FakeSession implements AsrSession {
  onPartial?: (text: string, stash: string) => void;
  result: Promise<AsrResult>;
  bytes = 0;
  cancelled = false;
  resolve!: (r: AsrResult) => void;
  reject!: (e: Error) => void;
  constructor(private finalText: string | Error) {
    this.result = new Promise((res, rej) => ((this.resolve = res), (this.reject = rej)));
    this.result.catch(() => {});
  }
  pushAudio(pcm: Uint8Array) {
    this.bytes += pcm.length;
    this.onPartial?.("", `heard ${this.bytes}`);
  }
  stop() {
    if (this.finalText instanceof Error) this.reject(this.finalText);
    else this.resolve({ text: this.finalText, asrText: "asr" });
  }
  cancel() {
    this.cancelled = true;
    this.reject(new Error("cancelled"));
  }
}

let finalText: string | Error = "สวัสดี hello.";
const sessions: FakeSession[] = [];
const provider: AsrProvider = {
  id: "fake",
  model: "fake-model",
  open: () => {
    const s = new FakeSession(finalText);
    sessions.push(s);
    return s;
  },
};

let cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.forEach((fn) => fn());
  cleanup = [];
  sessions.length = 0;
  finalText = "สวัสดี hello.";
});

function setup(maxUtteranceSeconds = 60, freshMs?: number) {
  const history = new History(":memory:");
  const server = createServer({
    config: { hostname: "127.0.0.1", port: 0, maxUtteranceSeconds, auth: { allowedUsers: ["me@github"], tokens: ["secret"] } },
    provider,
    history,
    freshMs,
  });
  cleanup.push(() => server.stop(true));
  const base = `http://127.0.0.1:${server.port}`;
  return { history, base, ws: `ws://127.0.0.1:${server.port}/v1/ws` };
}

/** Opens an authenticated socket; `next(type)` resolves with the next message of that type. */
async function connect(url: string) {
  const ws = new WebSocket(`${url}?device=test`, { headers: { "Tailscale-User-Login": "me@github" } } as any);
  const inbox: any[] = [];
  const waiters: { type: string; resolve: (m: any) => void }[] = [];
  ws.onmessage = (ev) => {
    const msg = JSON.parse(String(ev.data));
    const i = waiters.findIndex((w) => w.type === msg.type);
    if (i >= 0) waiters.splice(i, 1)[0]!.resolve(msg);
    else inbox.push(msg);
  };
  const next = (type: string) =>
    new Promise<any>((resolve) => {
      const i = inbox.findIndex((m) => m.type === type);
      if (i >= 0) resolve(inbox.splice(i, 1)[0]);
      else waiters.push({ type, resolve });
    });
  await new Promise((r) => (ws.onopen = r));
  cleanup.push(() => ws.close());
  const json = (o: object) => ws.send(JSON.stringify(o));
  return { ws, next, json, inbox };
}

/** A loud 440 Hz tone (counts as speech for the silence gate). */
const audio = (ms: number) => {
  const samples = new Int16Array(ms * 16);
  for (let i = 0; i < samples.length; i++) samples[i] = Math.round(8000 * Math.sin((2 * Math.PI * 440 * i) / 16000));
  return new Uint8Array(samples.buffer);
};
const silence = (ms: number) => new Uint8Array(ms * 32);

test("rejects unauthenticated requests, accepts Tailscale header or token", async () => {
  const { base } = setup();
  expect((await fetch(`${base}/v1/me`)).status).toBe(401);
  expect((await fetch(`${base}/v1/me`, { headers: { "Tailscale-User-Login": "stranger@github" } })).status).toBe(401);
  expect(await (await fetch(`${base}/v1/me`, { headers: { "Tailscale-User-Login": "me@github" } })).json()).toEqual({ user: "me@github", model: "fake-model" });
  expect((await fetch(`${base}/v1/me`, { headers: { Authorization: "Bearer secret" } })).status).toBe(200);
  expect((await fetch(`${base}/v1/me?token=secret`)).status).toBe(200);
  expect((await fetch(`${base}/healthz`)).status).toBe(200);
});

test("streams an utterance: partials, cleaned final, saved to history", async () => {
  const { base, ws: url } = setup();
  const c = await connect(url);
  expect(await c.next("ready")).toMatchObject({ user: "me@github", model: "fake-model" });

  c.json({ type: "start", utteranceId: "u1" });
  c.ws.send(audio(200));
  c.ws.send(audio(200));
  expect(await c.next("partial")).toEqual({ type: "partial", utteranceId: "u1", text: "", stash: "heard 6400" });
  c.json({ type: "stop", utteranceId: "u1" });

  const final = await c.next("final");
  expect(final).toMatchObject({ utteranceId: "u1", text: "สวัสดี hello", durationMs: 400 });
  expect(final.id).toBeNumber();

  const res = await fetch(`${base}/v1/history`, { headers: { "Tailscale-User-Login": "me@github" } });
  expect(((await res.json()) as any).items).toEqual([expect.objectContaining({ id: final.id, text: "สวัสดี hello", device: "test", model: "fake-model" })]);

  const del = await fetch(`${base}/v1/history/${final.id}`, { method: "DELETE", headers: { "Tailscale-User-Login": "me@github" } });
  expect(del.status).toBe(204);
});

test("silence returns an empty final without asking the provider", async () => {
  const { history, ws: url } = setup();
  const c = await connect(url);
  c.json({ type: "start", utteranceId: "u1" });
  c.ws.send(silence(2000));
  c.ws.send(audio(200)); // a short blip isn't speech either
  c.json({ type: "stop", utteranceId: "u1" });
  expect(await c.next("final")).toMatchObject({ id: null, text: "" });
  expect(sessions[0]!.cancelled).toBe(true);
  expect(history.list("me@github")).toEqual([]);
});

test("rejects a second start while busy, and allows a new one afterwards", async () => {
  const { ws: url } = setup();
  const c = await connect(url);
  c.json({ type: "start", utteranceId: "u1" });
  c.json({ type: "start", utteranceId: "u2" });
  expect(await c.next("error")).toMatchObject({ utteranceId: "u2", code: "busy" });
  c.json({ type: "cancel", utteranceId: "u1" });
  c.json({ type: "start", utteranceId: "u3" });
  c.ws.send(audio(400));
  c.json({ type: "stop", utteranceId: "u3" });
  expect(await c.next("final")).toMatchObject({ utteranceId: "u3", text: "สวัสดี hello" });
  expect(sessions[0]!.cancelled).toBe(true);
});

test("upstream failure is reported as an error and nothing is saved", async () => {
  finalText = new Error("boom");
  const { history, ws: url } = setup();
  const c = await connect(url);
  c.json({ type: "start", utteranceId: "u1" });
  c.ws.send(audio(400));
  c.json({ type: "stop", utteranceId: "u1" });
  expect(await c.next("error")).toMatchObject({ utteranceId: "u1", code: "upstream", message: "boom" });
  expect(history.list("me@github")).toEqual([]);
});

test("auto-stops at the length limit", async () => {
  const { ws: url } = setup(0.4);
  const c = await connect(url);
  c.json({ type: "start", utteranceId: "u1" });
  c.ws.send(audio(200));
  c.ws.send(audio(200));
  c.ws.send(audio(200)); // dropped: already stopped
  expect(await c.next("error")).toMatchObject({ code: "too_long" });
  expect(await c.next("final")).toMatchObject({ text: "สวัสดี hello", durationMs: 400 });
});

test("closing the socket cancels the active utterance", async () => {
  const { ws: url } = setup();
  const c = await connect(url);
  c.json({ type: "start", utteranceId: "u1" });
  await c.next("ready");
  c.ws.close();
  await Bun.sleep(50);
  expect(sessions[0]!.cancelled).toBe(true);
});

/** Opens a desktop socket for `user`; collects transcripts. */
async function desktop(base: string, name: string, headers: Record<string, string> = { "Tailscale-User-Login": "me@github" }) {
  const ws = new WebSocket(`${base.replace("http", "ws")}/v1/desktop?name=${name}`, { headers } as any);
  const got: any[] = [];
  const hello = new Promise<any>((resolve) => {
    ws.onmessage = (ev) => {
      const msg = JSON.parse(String(ev.data));
      if (msg.type === "hello") resolve(msg);
      else got.push(msg);
    };
  });
  cleanup.push(() => ws.close());
  return { ws, got, hello: await hello };
}

test("broadcasts finals to every desktop of the same user only", async () => {
  const { base, ws: url } = setup();
  const mac = await desktop(base, "MacBook");
  const pc = await desktop(base, "Desktop-PC");
  const other = await desktop(base, "Theirs", { Authorization: "Bearer secret" }); // a different user
  expect(mac.hello).toEqual({ type: "hello", user: "me@github" });
  expect(other.hello.user).not.toBe("me@github");

  const c = await connect(url);
  c.json({ type: "start", utteranceId: "u1" });
  c.ws.send(audio(400));
  c.json({ type: "stop", utteranceId: "u1" });
  const final = await c.next("final");
  expect(final.delivered.sort()).toEqual(["Desktop-PC", "MacBook"]);

  await Bun.sleep(50);
  const expected = { type: "transcript", id: final.id, text: "สวัสดี hello", device: "test", createdAt: expect.any(Number), fresh: true };
  expect(mac.got).toEqual([expected]);
  expect(pc.got).toEqual([expected]);
  expect(other.got).toEqual([]);
});

test("transcripts that take too long after stop are not fresh", async () => {
  const { base, ws: url } = setup(60, 0); // any latency exceeds the window
  const mac = await desktop(base, "MacBook");
  const c = await connect(url);
  c.json({ type: "start", utteranceId: "u1" });
  c.ws.send(audio(400));
  c.json({ type: "stop", utteranceId: "u1" });
  await c.next("final");
  await Bun.sleep(50);
  expect(mac.got).toEqual([expect.objectContaining({ type: "transcript", text: "สวัสดี hello", fresh: false })]);
});

test("silent finals are not broadcast; closed desktops are forgotten", async () => {
  const { base, ws: url } = setup();
  const mac = await desktop(base, "MacBook");
  const c = await connect(url);
  c.json({ type: "start", utteranceId: "u1" });
  c.ws.send(silence(500));
  c.json({ type: "stop", utteranceId: "u1" });
  expect(await c.next("final")).toMatchObject({ text: "", delivered: [] });

  mac.ws.close();
  await Bun.sleep(50);
  c.json({ type: "start", utteranceId: "u2" });
  c.ws.send(audio(400));
  c.json({ type: "stop", utteranceId: "u2" });
  expect(await c.next("final")).toMatchObject({ text: "สวัสดี hello", delivered: [] });
  expect(mac.got).toEqual([]);
});

test("desktop channel requires auth", async () => {
  const { base } = setup();
  expect((await fetch(`${base}/v1/desktop`)).status).toBe(401);
});
