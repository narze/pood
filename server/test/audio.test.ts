import { expect, test } from "bun:test";
import { dbfs } from "../src/audio";

test("dbfs of silence, full-scale and half-scale", () => {
  expect(dbfs(new Uint8Array(3200))).toBe(-Infinity);
  expect(dbfs(new Uint8Array(new Int16Array(1600).fill(-32768).buffer))).toBeCloseTo(0, 1);
  expect(dbfs(new Uint8Array(new Int16Array(1600).fill(16384).buffer))).toBeCloseTo(-6.02, 1);
});

test("works on views at an odd byte offset", () => {
  const buf = new Uint8Array(3201);
  buf.set(new Uint8Array(new Int16Array(1600).fill(16384).buffer), 1);
  expect(dbfs(buf.subarray(1))).toBeCloseTo(-6.02, 1);
});
