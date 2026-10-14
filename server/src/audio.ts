export const BYTES_PER_MS = 32; // 16 kHz × 16-bit mono

/**
 * Loudness above which a chunk counts as possible speech. Recorded speech sits around -35…-20 dBFS; digital silence is
 * -120 and quiet room noise about -65. Qwen-Omni invents whole sentences from silence or noise, so audio that never
 * crosses this level must not reach the model.
 */
export const VOICE_DBFS = -50;

/** RMS level of a PCM16LE chunk in dBFS. */
export function dbfs(pcm: Uint8Array): number {
  const n = pcm.length >> 1;
  if (!n) return -Infinity;
  let sum = 0;
  for (let i = 0; i < n * 2; i += 2) {
    const s = ((pcm[i + 1]! << 24) >> 16) | pcm[i]!; // little-endian, sign-extended; works at any byte offset
    sum += s * s;
  }
  return 10 * Math.log10(sum / n / 32768 ** 2);
}
