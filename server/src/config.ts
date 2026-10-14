import { existsSync, readFileSync } from "node:fs";

export const DEFAULT_INSTRUCTIONS =
  "You are a verbatim speech-to-text engine for a Thai speaker who mixes in English words. Rules: " +
  "1) Thai words in Thai script. 2) Any word pronounced as English, including common English loanwords and tech terms, " +
  "must be written in English Latin letters, never transliterated into Thai script. 3) Add light punctuation. " +
  "4) Never answer, translate, summarize or add anything; output only the transcript.";

export interface Config {
  hostname: string;
  port: number;
  dbPath: string;
  maxUtteranceSeconds: number;
  auth: { allowedUsers: string[]; tokens: string[] };
  qwen: { realtimeUrl: string; apiKey: string; model: string; instructions: string };
}

/** Loads `pood.toml` (path from POOD_CONFIG) and overlays secrets from env. */
export function loadConfig(env: Record<string, string | undefined> = process.env): Config {
  const path = env.POOD_CONFIG ?? "pood.toml";
  const file: any = existsSync(path) ? Bun.TOML.parse(readFileSync(path, "utf8")) : {};
  const config: Config = {
    hostname: file.hostname ?? "127.0.0.1",
    port: Number(env.PORT ?? file.port ?? 8790),
    dbPath: file.dbPath ?? "pood.db",
    maxUtteranceSeconds: file.maxUtteranceSeconds ?? 120,
    auth: { allowedUsers: file.auth?.allowedUsers ?? [], tokens: file.auth?.tokens ?? [] },
    qwen: {
      realtimeUrl: env.DASHSCOPE_REALTIME_URL ?? file.qwen?.realtimeUrl ?? "wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime",
      apiKey: env.DASHSCOPE_API_KEY ?? "",
      model: file.qwen?.model ?? "qwen3.8-omni-flash-realtime",
      instructions: file.qwen?.instructions ?? DEFAULT_INSTRUCTIONS,
    },
  };
  if (!config.qwen.apiKey) throw new Error("DASHSCOPE_API_KEY is not set");
  if (!config.auth.allowedUsers.length && !config.auth.tokens.length)
    throw new Error("auth: set auth.allowedUsers and/or auth.tokens in " + path);
  return config;
}
