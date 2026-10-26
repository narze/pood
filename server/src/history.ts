import { Database } from "bun:sqlite";

export interface Transcript {
  id: number;
  createdAt: number;
  device: string;
  text: string;
  model: string;
  durationMs: number;
  latencyMs: number;
}

export interface NewTranscript extends Omit<Transcript, "id" | "createdAt"> {
  user: string;
  asrText: string;
}

/** Transcript history, scoped per user. */
export class History {
  private db: Database;

  constructor(path: string) {
    this.db = new Database(path, { create: true, strict: true });
    this.db.run("PRAGMA journal_mode = WAL");
    this.db.run(`CREATE TABLE IF NOT EXISTS transcripts (
      id INTEGER PRIMARY KEY,
      created_at INTEGER NOT NULL,
      user TEXT NOT NULL,
      device TEXT NOT NULL,
      text TEXT NOT NULL,
      asr_text TEXT NOT NULL,
      model TEXT NOT NULL,
      duration_ms INTEGER NOT NULL,
      latency_ms INTEGER NOT NULL
    )`);
    this.db.run("CREATE INDEX IF NOT EXISTS transcripts_user_id ON transcripts (user, id)");
  }

  add(t: NewTranscript, createdAt = Date.now()): Transcript {
    const { id } = this.db
      .query<{ id: number }, any>(
        `INSERT INTO transcripts (created_at, user, device, text, asr_text, model, duration_ms, latency_ms)
         VALUES ($createdAt, $user, $device, $text, $asrText, $model, $durationMs, $latencyMs) RETURNING id`,
      )
      .get({ createdAt, ...t })!;
    return { id, createdAt, device: t.device, text: t.text, model: t.model, durationMs: t.durationMs, latencyMs: t.latencyMs };
  }

  list(user: string, opts: { before?: number; limit?: number } = {}): Transcript[] {
    return this.db
      .query<Transcript, any>(
        `SELECT id, created_at AS createdAt, device, text, model, duration_ms AS durationMs, latency_ms AS latencyMs
         FROM transcripts WHERE user = $user AND id < $before ORDER BY id DESC LIMIT $limit`,
      )
      .all({ user, before: opts.before ?? Number.MAX_SAFE_INTEGER, limit: opts.limit ?? 50 });
  }

  get(user: string, id: number): Transcript | null {
    return this.db
      .query<Transcript, any>(
        `SELECT id, created_at AS createdAt, device, text, model, duration_ms AS durationMs, latency_ms AS latencyMs
         FROM transcripts WHERE user = $user AND id = $id`,
      )
      .get({ user, id });
  }

  delete(user: string, id: number): boolean {
    return this.db.query("DELETE FROM transcripts WHERE user = $user AND id = $id").run({ user, id }).changes > 0;
  }

  close() {
    this.db.close();
  }
}
