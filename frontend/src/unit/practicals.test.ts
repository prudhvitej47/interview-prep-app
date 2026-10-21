import { describe, expect, it } from "vitest";
import { isRightNumber, markLines, parseEvidence, parsePredict, readNumber } from "./practicals";

describe("parsePredict", () => {
  it("reads a choice question", () => {
    const spec = parsePredict(JSON.stringify({
      question: "What happens?", choices: ["It hangs", "It throws"], answer: 0, reveal: ["It hangs.", "Every time."],
    }));
    expect(spec).toEqual({ kind: "choice", question: "What happens?", choices: ["It hangs", "It throws"],
      answer: 0, reveal: "It hangs.\nEvery time." });
  });

  it("reads a number question with tolerance, unit and output", () => {
    const spec = parsePredict(JSON.stringify({
      question: "How many runs froze?", number: 5, tolerance: 0, unit: "runs", reveal: "All of them.",
      output: ["v1: stuck", "v1: stuck"],
    }));
    expect(spec).toEqual({ kind: "number", question: "How many runs froze?", number: 5, tolerance: 0,
      unit: "runs", reveal: "All of them.", output: "v1: stuck\nv1: stuck" });
  });

  it("defaults tolerance to 0", () => {
    const spec = parsePredict(JSON.stringify({ question: "q", number: 3, reveal: "r" }));
    expect(spec.kind === "number" && spec.tolerance).toBe(0);
  });

  it.each([
    ["not JSON", "{", /JSON/],
    ["no question", JSON.stringify({ choices: ["a", "b"], answer: 0, reveal: "r" }), /question/],
    ["no reveal", JSON.stringify({ question: "q", choices: ["a", "b"], answer: 0 }), /reveal/],
    ["both kinds", JSON.stringify({ question: "q", choices: ["a", "b"], answer: 0, number: 1, reveal: "r" }), /either/],
    ["neither kind", JSON.stringify({ question: "q", reveal: "r" }), /either/],
    ["one choice", JSON.stringify({ question: "q", choices: ["a"], answer: 0, reveal: "r" }), /2 to 6/],
    ["answer out of range", JSON.stringify({ question: "q", choices: ["a", "b"], answer: 2, reveal: "r" }), /answer/],
    ["negative tolerance", JSON.stringify({ question: "q", number: 1, tolerance: -1, reveal: "r" }), /tolerance/],
    ["unknown field", JSON.stringify({ question: "q", number: 1, reveal: "r", hint: "x" }), /unknown field hint/],
  ])("rejects %s", (_name, source, message) => {
    expect(() => parsePredict(source)).toThrow(message);
  });
});

describe("readNumber", () => {
  it.each([
    [" 5 ", 5], ["5.0", 5], ["1,000", 1000], ["-2.5", -2.5],
    // A lone comma before one or two digits is a decimal comma; groups of three are thousands.
    ["1,5", 1.5], ["-0,25", -0.25], ["12,345,678", 12345678], ["1,500.5", 1500.5],
    // Half-written decimals are what people type.
    ["5.", 5], [".5", 0.5], ["-.5", -0.5],
    // A leading zero before the comma is a decimal, never thousands.
    ["0,123", 0.123],
  ])("reads %j as %d", (raw, value) => expect(readNumber(raw)).toBe(value));

  it.each(["", "  ", "five", "5x", "1e"])("returns null for %j", (raw) => expect(readNumber(raw)).toBeNull());
});

describe("isRightNumber", () => {
  const spec = { kind: "number" as const, question: "q", number: 10, tolerance: 1, reveal: "r" };
  it("accepts values within tolerance, inclusive", () => {
    expect(isRightNumber(spec, 9)).toBe(true);
    expect(isRightNumber(spec, 11)).toBe(true);
    expect(isRightNumber(spec, 11.5)).toBe(false);
  });

  it("accepts a decimal answer exactly at the tolerance edge, despite floating point", () => {
    const decimal = { kind: "number" as const, question: "q", number: 2.5, tolerance: 0.1, reveal: "r" };
    expect(isRightNumber(decimal, 2.6)).toBe(true);
    expect(isRightNumber(decimal, 2.4)).toBe(true);
    expect(isRightNumber(decimal, 2.61)).toBe(false);
  });
});

describe("parseEvidence", () => {
  const good = { prompt: "Which lines?", lines: ["a", "b", "c"], cause: [3, 1], explain: ["Because", "reasons."] };

  it("reads a fence and sorts the cause lines", () => {
    expect(parseEvidence(JSON.stringify(good))).toEqual({
      prompt: "Which lines?", lines: ["a", "b", "c"], cause: [1, 3], explain: "Because\nreasons." });
  });

  it.each([
    ["no lines", { ...good, lines: [] }, /lines/],
    ["lines not strings", { ...good, lines: ["a", 2] }, /lines/],
    ["no cause", { ...good, cause: [] }, /cause/],
    ["cause past the end", { ...good, cause: [4] }, /line 4/],
    ["cause zero", { ...good, cause: [0] }, /line 0/],
    ["duplicate cause", { ...good, cause: [1, 1] }, /twice/],
    ["no explain", { prompt: "p", lines: ["a"], cause: [1] }, /explain/],
  ])("rejects %s", (_name, spec, message) => {
    expect(() => parseEvidence(JSON.stringify(spec))).toThrow(message);
  });
});

describe("markLines", () => {
  const spec = { prompt: "p", lines: ["a", "b", "c", "d"], cause: [2, 4], explain: "e" };
  it("is right only for exactly the cause lines", () => {
    expect(markLines(spec, [4, 2])).toEqual({ right: true, missed: [], extra: [] });
  });
  it("names missed and extra lines in order", () => {
    expect(markLines(spec, [3, 2, 1])).toEqual({ right: false, missed: [4], extra: [1, 3] });
  });
});

describe("readNumber refuses", () => {
  it.each(["abc", ".", "-", "1,2,3", "1,5.2", "1.2.3", "", "9".repeat(400)])("%j", (raw) => expect(readNumber(raw)).toBeNull());
});
