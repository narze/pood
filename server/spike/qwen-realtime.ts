// M0 spike: stream 16 kHz PCM16 clips to Qwen-Omni realtime and compare Omni text vs built-in ASR text.
// Usage: bun spike/qwen-realtime.ts [model] [clipId...]
import { readFileSync } from "node:fs";

const KEY = process.env.DASHSCOPE_API_KEY!;
const BASE = process.env.DASHSCOPE_REALTIME_URL!;
const model = process.argv[2] ?? "qwen3.5-omni-flash-realtime";
const dir = `${import.meta.dir}/clips`;
const refs = readFileSync(`${dir}/refs.tsv`, "utf8").trim().split("\n").map((l) => l.split("\t"));
const only = process.argv.slice(3);

const INSTRUCTIONS = process.env.PROMPT ||
  "You are a verbatim speech-to-text engine for a Thai speaker who mixes in English words. Rules: 1) Thai words in Thai script. 2) Any word pronounced as English, including common English loanwords and tech terms, must be written in English Latin letters, never transliterated into Thai script. 3) Add light punctuation. 4) Never answer, translate, summarize or add anything; output only the transcript.";

function run(id: string, ref: string) {
  return new Promise<void>((resolve) => {
    const pcm = readFileSync(`${dir}/${id}.pcm`);
    const ws = new WebSocket(`${BASE}?model=${model}`, { headers: { Authorization: `Bearer ${KEY}` } } as any);
    const t0 = performance.now();
    let tStop = 0, tFinal = 0, omni = "", asr = "", partials = 0;
    const send = (o: object) => ws.send(JSON.stringify(o));
    const done = (why: string) => {
      console.log(`\n[${model}] ${id} (${why})\n  ref : ${ref}\n  omni: ${omni}\n  asr : ${asr}\n  partials=${partials} stop→final=${Math.round(tFinal - tStop)}ms`);
      ws.close();
      resolve();
    };
    const timer = setTimeout(() => done("timeout"), 30000);

    ws.onmessage = async (ev) => {
      const e = JSON.parse(ev.data as string);
      switch (e.type) {
        case "session.created":
          send({
            type: "session.update",
            session: {
              modalities: ["text"],
              instructions: INSTRUCTIONS,
              input_audio_format: "pcm16",
              input_audio_transcription: { model: "qwen3-asr-flash-realtime" },
              turn_detection: null,
            },
          });
          break;
        case "session.updated":
          for (let i = 0; i < pcm.length; i += 3200) {
            send({ type: "input_audio_buffer.append", audio: pcm.subarray(i, i + 3200).toString("base64") });
            await Bun.sleep(20); // faster than realtime, still chunked
          }
          tStop = performance.now();
          send({ type: "input_audio_buffer.commit" });
          send({ type: "response.create" });
          break;
        case "conversation.item.input_audio_transcription.delta":
          partials++; if (process.env.VERBOSE) console.log("  delta", JSON.stringify({ text: e.text, stash: e.stash, delta: e.delta }));
          break;
        case "conversation.item.input_audio_transcription.completed":
          asr = e.transcript;
          break;
        case "response.text.delta":
          omni += e.delta;
          break;
        case "response.done":
          tFinal = performance.now();
          clearTimeout(timer);
          setTimeout(() => done("ok"), asr ? 0 : 1500); // give ASR a moment if it lags
          break;
        case "error":
          console.error(`  ${id} error:`, JSON.stringify(e.error ?? e));
          break;
        default:
          if (process.env.VERBOSE) console.log("  evt", e.type);
      }
    };
    ws.onerror = (e) => { console.error(`  ${id} ws error`, e); clearTimeout(timer); resolve(); };
    ws.onclose = (e) => { if (e.code !== 1000 && e.code !== 1005) console.error(`  ${id} closed ${e.code} ${e.reason} after ${Math.round(performance.now() - t0)}ms`); };
  });
}

for (const [id, , ref] of refs) if (!only.length || only.includes(id)) await run(id, ref);
