import type { AsrProvider, AsrResult, AsrSession } from "../provider";

export interface QwenOmniOptions {
  realtimeUrl: string;
  apiKey: string;
  model: string;
  instructions: string;
  /** Upper bound from stop() to the final transcript. */
  finalTimeoutMs?: number;
}

/** DashScope Qwen-Omni realtime: Omni gives the final text, its built-in ASR gives live partials. */
export function qwenOmni(opts: QwenOmniOptions): AsrProvider {
  return { id: "qwen-omni", model: opts.model, open: () => new QwenOmniSession(opts) };
}

class QwenOmniSession implements AsrSession {
  onPartial?: (text: string, stash: string) => void;
  readonly result: Promise<AsrResult>;
  private ws: WebSocket;
  private ready = false;
  private queue: string[] = [];
  private settled = false;
  private omni = "";
  private asr = "";
  private resolve!: (r: AsrResult) => void;
  private reject!: (e: Error) => void;

  constructor(private opts: QwenOmniOptions) {
    this.result = new Promise((res, rej) => ((this.resolve = res), (this.reject = rej)));
    this.result.catch(() => {}); // callers attach their own handlers; avoid unhandled-rejection noise
    this.ws = new WebSocket(`${opts.realtimeUrl}?model=${encodeURIComponent(opts.model)}`, {
      headers: { Authorization: `Bearer ${opts.apiKey}` },
    } as any);
    this.ws.onmessage = (ev) => this.handle(JSON.parse(String(ev.data)));
    this.ws.onerror = () => this.fail(new Error("upstream connection error"));
    this.ws.onclose = (ev) => this.fail(new Error(`upstream closed (${ev.code}${ev.reason ? ` ${ev.reason}` : ""})`));
  }

  pushAudio(pcm: Uint8Array) {
    this.send({ type: "input_audio_buffer.append", audio: Buffer.from(pcm).toString("base64") });
  }

  stop() {
    this.send({ type: "input_audio_buffer.commit" });
    this.send({ type: "response.create" });
    const timer = setTimeout(() => this.fail(new Error("timed out waiting for final transcript")), this.opts.finalTimeoutMs ?? 15000);
    this.result.finally(() => clearTimeout(timer)).catch(() => {});
  }

  cancel() {
    this.fail(new Error("cancelled"));
  }

  private send(msg: object) {
    const data = JSON.stringify(msg);
    if (this.ready) this.ws.send(data);
    else this.queue.push(data);
  }

  private handle(e: any) {
    switch (e.type) {
      case "session.created":
        this.ws.send(JSON.stringify({
          type: "session.update",
          session: {
            modalities: ["text"],
            instructions: this.opts.instructions,
            input_audio_format: "pcm16",
            input_audio_transcription: { model: "qwen3-asr-flash-realtime" },
            turn_detection: null,
          },
        }));
        break;
      case "session.updated":
        this.ready = true;
        for (const data of this.queue.splice(0)) this.ws.send(data);
        break;
      case "conversation.item.input_audio_transcription.delta":
        this.asr = (e.text ?? "") + (e.stash ?? "");
        this.onPartial?.(e.text ?? "", e.stash ?? "");
        break;
      case "conversation.item.input_audio_transcription.completed":
        this.asr = e.transcript ?? this.asr;
        break;
      case "response.text.delta":
        this.omni += e.delta ?? "";
        break;
      case "response.text.done":
        if (typeof e.text === "string") this.omni = e.text;
        break;
      case "response.done":
        this.settle(() => this.resolve({ text: this.omni, asrText: this.asr }));
        break;
      case "error":
        this.fail(new Error(e.error?.message ?? "upstream error"));
        break;
    }
  }

  private fail(err: Error) {
    this.settle(() => this.reject(err));
  }

  private settle(fn: () => void) {
    if (this.settled) return;
    this.settled = true;
    fn();
    this.ws.onclose = this.ws.onerror = null;
    if (this.ws.readyState <= WebSocket.OPEN) this.ws.close();
  }
}
