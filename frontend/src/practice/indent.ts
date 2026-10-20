/** What a key does to the code: the new text and where the caret goes. */
export type Edit = { value: string; caret: number };

const LEVEL = "    ";

/**
 * Tab puts four spaces in place of the selection; Enter starts the next line at the same indent, one
 * level deeper after an opening brace. Just enough for writing Java in a plain field (runner design §2).
 */
export function keyEdit(value: string, start: number, end: number, key: "Tab" | "Enter"): Edit {
  const before = value.slice(0, start);
  const after = value.slice(end);
  if (key === "Tab") return { value: before + LEVEL + after, caret: start + LEVEL.length };
  const line = before.slice(before.lastIndexOf("\n") + 1);
  const indent = /^[ \t]*/.exec(line)![0];
  const insert = "\n" + indent + (line.trimEnd().endsWith("{") ? LEVEL : "");
  return { value: before + insert + after, caret: start + insert.length };
}
