import { expect, test } from "bun:test";
import { cleanTranscript } from "../src/cleanup";

test.each([
  ["darkมากอย่างกับจักรวาล dc", "dark มากอย่างกับจักรวาล dc"],
  ["ดาร์กมาก อย่างกับจักรวาล DC.", "ดาร์กมาก อย่างกับจักรวาล DC"],
  ["ใช้Gitอยู่", "ใช้ Git อยู่"],
  ["Git is easy until two people have ideas at the same time.", "Git is easy until two people have ideas at the same time."],
  ["  สั่งอาหาร   สำเร็จ \n", "สั่งอาหาร สำเร็จ"],
  ["ราคา 500บาท", "ราคา 500บาท"],
  ["", ""],
])("cleanTranscript(%p)", (input, expected) => {
  expect(cleanTranscript(input)).toBe(expected);
});
