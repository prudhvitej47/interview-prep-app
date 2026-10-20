import { describe, expect, it } from "vitest";
import { keyEdit } from "./indent";

describe("keyEdit", () => {
  it("Tab puts four spaces in place of the selection", () => {
    expect(keyEdit("ab", 1, 1, "Tab")).toEqual({ value: "a    b", caret: 5 });
    expect(keyEdit("abcd", 1, 3, "Tab")).toEqual({ value: "a    d", caret: 5 });
  });

  it("Enter keeps the line's indent, and adds a level after an opening brace", () => {
    expect(keyEdit("    int x;", 10, 10, "Enter")).toEqual({ value: "    int x;\n    ", caret: 15 });
    expect(keyEdit("    if (a) {", 12, 12, "Enter")).toEqual({ value: "    if (a) {\n        ", caret: 21 });
    expect(keyEdit("x", 1, 1, "Enter")).toEqual({ value: "x\n", caret: 2 });
  });
});
