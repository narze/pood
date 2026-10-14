import { expect, test } from "bun:test";
import { History } from "../src/history";

const entry = (user: string, text: string) => ({
  user, text, device: "pixel", asrText: "", model: "m", durationMs: 1000, latencyMs: 300,
});

test("lists newest first, scoped per user, paged with before", () => {
  const h = new History(":memory:");
  const a1 = h.add(entry("a", "one"));
  const a2 = h.add(entry("a", "two"));
  h.add(entry("b", "other"));
  const a3 = h.add(entry("a", "three"));

  expect(h.list("a").map((t) => t.text)).toEqual(["three", "two", "one"]);
  expect(h.list("a", { limit: 2 }).map((t) => t.id)).toEqual([a3.id, a2.id]);
  expect(h.list("a", { before: a2.id }).map((t) => t.id)).toEqual([a1.id]);
  expect(h.list("b").map((t) => t.text)).toEqual(["other"]);
});

test("delete only removes the caller's own transcript", () => {
  const h = new History(":memory:");
  const t = h.add(entry("a", "mine"));
  expect(h.delete("b", t.id)).toBe(false);
  expect(h.delete("a", t.id)).toBe(true);
  expect(h.list("a")).toEqual([]);
});
