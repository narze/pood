// Live test against DashScope; runs only with LIVE=1 (needs DASHSCOPE_API_KEY and a recorded clip).
import { expect, test } from "bun:test";
import { existsSync, readFileSync } from "node:fs";
import { DEFAULT_INSTRUCTIONS } from "../src/config";
import { qwenOmni } from "../src/providers/qwen-omni";

const clip = `${import.meta.dir}/../spike/clips/me-1791971473304.pcm`;

test.skipIf(!process.env.LIVE || !existsSync(clip))("qwen-omni transcribes a Thai clip", async () => {
  const provider = qwenOmni({
    realtimeUrl: process.env.DASHSCOPE_REALTIME_URL!,
    apiKey: process.env.DASHSCOPE_API_KEY!,
    model: "qwen3.8-omni-flash-realtime",
    instructions: DEFAULT_INSTRUCTIONS,
  });
  const session = provider.open();
  let partials = 0;
  session.onPartial = () => partials++;
  const pcm = readFileSync(clip);
  for (let i = 0; i < pcm.length; i += 3200) session.pushAudio(pcm.subarray(i, i + 3200));
  session.stop();
  const { text } = await session.result;
  expect(text).toContain("สั่งอาหารสำเร็จ");
  expect(partials).toBeGreaterThan(0);
}, 30000);

test.skipIf(!process.env.LIVE)("qwen-omni rejects a bad API key", async () => {
  const session = qwenOmni({
    realtimeUrl: process.env.DASHSCOPE_REALTIME_URL!,
    apiKey: "sk-invalid",
    model: "qwen3.8-omni-flash-realtime",
    instructions: DEFAULT_INSTRUCTIONS,
  }).open();
  session.stop();
  await expect(session.result).rejects.toThrow();
}, 30000);
