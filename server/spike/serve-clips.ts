// Serve spike clips as WAV + a listening page + a recording page. Usage: bun spike/serve-clips.ts [port]
import { appendFileSync, existsSync, readFileSync, writeFileSync } from "node:fs";

const dir = `${import.meta.dir}/clips`;
const port = Number(process.argv[2] ?? 8788);
const esc = (s: string) => s.replace(/[&<>"]/g, (c) => `&#${c.charCodeAt(0)};`);
const html = (title: string, body: string) =>
  new Response(
    `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>${title}</title>` +
      `<style>body{font:16px system-ui;max-width:40rem;margin:2rem auto;padding:0 1rem}li{margin:1.5rem 0}audio{width:100%}` +
      `input,textarea,button{font:inherit;width:100%;box-sizing:border-box;margin:.25rem 0 1rem;padding:.5rem}` +
      `form.ref button{width:auto;margin-top:0}#rec{font-size:1.5rem;padding:1.5rem}#rec.on{background:#e33;color:#fff}</style>${body}`,
    { headers: { "content-type": "text/html; charset=utf-8" } },
  );
const readRefs = () => readFileSync(`${dir}/refs.tsv`, "utf8").trim().split("\n").map((l) => l.split("\t"));

function wav(pcm: Buffer) {
  const h = Buffer.alloc(44);
  h.write("RIFF", 0); h.writeUInt32LE(36 + pcm.length, 4); h.write("WAVEfmt ", 8);
  h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22);
  h.writeUInt32LE(16000, 24); h.writeUInt32LE(32000, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34);
  h.write("data", 36); h.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([h, pcm]);
}

// Browser recording (webm/opus or mp4/aac) → 16 kHz mono PCM16 via ffmpeg.
async function toPcm(audio: Uint8Array) {
  const p = Bun.spawn(["ffmpeg", "-loglevel", "error", "-i", "pipe:0", "-ar", "16000", "-ac", "1", "-f", "s16le", "pipe:1"], {
    stdin: audio, stdout: "pipe", stderr: "pipe",
  });
  const [out, err, code] = await Promise.all([new Response(p.stdout).arrayBuffer(), new Response(p.stderr).text(), p.exited]);
  if (code !== 0) throw new Error(err || `ffmpeg exited ${code}`);
  return Buffer.from(out);
}

async function saveClip(req: Request) {
  const form = await req.formData();
  const audio = form.get("audio");
  const text = String(form.get("text") ?? "").replace(/[\t\r\n]+/g, " ").trim();
  let id = String(form.get("id") ?? "").trim() || `me-${Date.now()}`;
  if (!(audio instanceof Blob) || !/^[\w-]+$/.test(id)) return new Response("bad id or audio", { status: 400 });
  if (readRefs().some(([x]) => x === id) || existsSync(`${dir}/${id}.pcm`)) return new Response(`id "${id}" exists`, { status: 409 });
  const pcm = await toPcm(new Uint8Array(await audio.arrayBuffer()));
  writeFileSync(`${dir}/${id}.pcm`, pcm);
  appendFileSync(`${dir}/refs.tsv`, `${id}\tme\t${text}\n`);
  return Response.json({ id, seconds: pcm.length / 32000 });
}

async function editClip(req: Request, id: string) {
  const text = String((await req.json()).text ?? "").replace(/[\t\r\n]+/g, " ").trim();
  const refs = readRefs();
  const row = refs.find(([x]) => x === id);
  if (!row) return new Response("not found", { status: 404 });
  row[2] = text;
  writeFileSync(`${dir}/refs.tsv`, refs.map((r) => r.join("\t")).join("\n") + "\n");
  return Response.json({ id, text });
}

const EDIT_SCRIPT = `<script>
document.querySelectorAll("form.ref").forEach((f) => f.onsubmit = async (e) => {
  e.preventDefault();
  const r = await fetch("/clips/" + f.dataset.id, { method: "PUT", headers: { "content-type": "application/json" }, body: JSON.stringify({ text: f.text.value }) });
  f.querySelector("small").textContent = r.ok ? "saved" : "error: " + (await r.text());
});
</script>`;

const RECORD_PAGE = `<h1>Record a clip</h1><p><a href="/">← all clips</a></p>
<label>Clip id (optional, letters/digits/-/_)<input id="cid" placeholder="me-..."></label>
<label>What you'll say (reference text)<textarea id="text" rows="3" placeholder="วันนี้ต้อง deploy server ใหม่..."></textarea></label>
<button id="rec">● Record</button>
<audio id="preview" controls hidden></audio>
<button id="save" hidden>Save clip</button>
<p id="status"></p>
<script>
const $ = (id) => document.getElementById(id);
let rec, chunks = [], blob;
$("rec").onclick = async () => {
  if (rec?.state === "recording") return rec.stop();
  const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: false, noiseSuppression: false } });
  rec = new MediaRecorder(stream); chunks = [];
  rec.ondataavailable = (e) => chunks.push(e.data);
  rec.onstop = () => {
    stream.getTracks().forEach((t) => t.stop());
    blob = new Blob(chunks, { type: rec.mimeType });
    $("preview").src = URL.createObjectURL(blob); $("preview").hidden = $("save").hidden = false;
    $("rec").textContent = "● Re-record"; $("rec").classList.remove("on");
  };
  rec.start(); $("rec").textContent = "■ Stop"; $("rec").classList.add("on"); $("status").textContent = "";
};
$("save").onclick = async () => {
  const fd = new FormData();
  fd.append("audio", blob); fd.append("id", $("cid").value); fd.append("text", $("text").value);
  $("status").textContent = "Saving…";
  const r = await fetch("/clips", { method: "POST", body: fd });
  if (!r.ok) return ($("status").textContent = "Error: " + (await r.text()));
  const j = await r.json();
  $("status").innerHTML = "Saved <b>" + j.id + "</b> (" + j.seconds.toFixed(1) + " s). Record another or <a href='/'>listen</a>.";
  $("save").hidden = $("preview").hidden = true; $("cid").value = ""; $("text").value = ""; $("rec").textContent = "● Record";
};
</script>`;

Bun.serve({
  hostname: "127.0.0.1",
  port,
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/clips" && req.method === "POST") {
      try { return await saveClip(req); } catch (e) { return new Response(String(e), { status: 500 }); }
    }
    const edit = path.match(/^\/clips\/([\w-]+)$/);
    if (edit && req.method === "PUT") return editClip(req, edit[1]);
    if (path === "/record") return html("Record clip", RECORD_PAGE);
    if (path === "/pood.apk") {
      const apk = Bun.file(`${import.meta.dir}/../../android/app/build/outputs/apk/debug/app-debug.apk`);
      if (!(await apk.exists())) return new Response("APK not built yet", { status: 404 });
      return new Response(apk, {
        headers: { "content-type": "application/vnd.android.package-archive", "content-disposition": 'attachment; filename="pood-debug.apk"' },
      });
    }
    const refs = readRefs();
    const m = path.match(/^\/([\w-]+)\.wav$/);
    if (m && refs.some(([id]) => id === m[1]))
      return new Response(wav(readFileSync(`${dir}/${m[1]}.pcm`)), { headers: { "content-type": "audio/wav" } });
    if (path !== "/") return new Response("not found", { status: 404 });
    const rows = refs
      .map(([id, voice, text]) => `<li><b>${esc(id)}</b> <small>(${esc(voice)})</small><form class="ref" data-id="${esc(id)}"><textarea name="text" rows="2">${esc(text ?? "")}</textarea><button>Save text</button> <small></small></form><audio controls preload="none" src="/${esc(id)}.wav"></audio></li>`)
      .join("");
    return html("Pood clips", `<h1>Pood spike clips</h1><p><a href="/pood.apk">⬇ Download Pood debug APK</a></p><p><a href="/record">● Record a new clip</a></p><p>16 kHz mono PCM16, exactly what is sent to the model.</p><ul>${rows}</ul>${EDIT_SCRIPT}`);
  },
});
console.log(`clips on http://127.0.0.1:${port}`);
