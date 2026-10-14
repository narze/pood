/** The seam between the gateway and a speech-to-text backend. */
export interface AsrProvider {
  id: string;
  model: string;
  /** Opens a session immediately; audio pushed before the upstream is ready is buffered by the session. */
  open(): AsrSession;
}

export interface AsrResult {
  text: string;
  /** Text from the provider's fast ASR pass, if it has one (kept for quality comparison). */
  asrText?: string;
}

export interface AsrSession {
  /** PCM16LE, 16 kHz, mono. */
  pushAudio(pcm: Uint8Array): void;
  /** End of speech; `result` resolves once the final transcript is ready. */
  stop(): void;
  /** Abort and release resources; `result` rejects. */
  cancel(): void;
  /** Settles once: resolves after `stop()`, rejects on upstream failure or `cancel()`. */
  readonly result: Promise<AsrResult>;
  onPartial?: (text: string, stash: string) => void;
}
