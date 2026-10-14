const THAI = "\\u0E00-\\u0E7F";

/** Normalizes model output into paste-ready text. */
export function cleanTranscript(raw: string): string {
  let text = raw.replace(/\s+/g, " ").trim();
  // Space between Latin words and Thai ("darkมาก" → "dark มาก"), as is conventional in Thai text.
  text = text
    .replace(new RegExp(`([A-Za-z])([${THAI}])`, "g"), "$1 $2")
    .replace(new RegExp(`([${THAI}])([A-Za-z])`, "g"), "$1 $2");
  // Thai doesn't end sentences with a period; the model sometimes adds one anyway.
  if (new RegExp(`[${THAI}]`).test(text)) text = text.replace(/\.$/, "");
  return text;
}
