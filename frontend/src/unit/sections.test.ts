import { describe, expect, it } from "vitest";
import { splitSections, TYPE_LABEL } from "./sections";

// One body per unit type, using the sections validate.py requires for it.
const BODIES: Record<string, string[]> = {
  concept: ["Why it matters", "How it works", "Check yourself"],
  question: ["Prompt", "Key points of a strong answer", "Rubric", "Follow-ups"],
  coding: ["Problem", "Constraints", "Examples", "Hints", "Approach", "Solution", "Complexity",
    "Edge cases", "Follow-ups"],
  sql: ["Tables", "Question", "Expected output", "Solution"],
  lld: ["Requirements", "Entities", "Class diagram", "Concurrency", "What the interviewer is testing"],
  hld: ["Requirements", "Capacity estimation", "High-level design", "Deep dives", "Trade-offs",
    "Evolution", "Final architecture"],
  scenario: ["Situation", "Questions", "Expected diagnosis", "Remediation"],
  project: ["The system", "Question ladder", "Rubric"],
  behavioral: ["Situation", "Task", "Action", "Result"],
};

// What should start folded, because seeing it first would spoil the attempt.
const EXPECTED_FOLDED: Record<string, string[]> = {
  concept: [],
  question: ["Key points of a strong answer", "Rubric"],
  coding: ["Hints", "Approach", "Solution", "Complexity"],
  sql: ["Solution"],
  lld: [],
  hld: [],
  scenario: ["Expected diagnosis", "Remediation"],
  project: [],
  behavioral: [],
};

describe("unit layouts", () => {
  it.each(Object.keys(BODIES))("%s: every section renders, and only the spoilers fold", (type) => {
    const markdown = "Intro text.\n\n" + BODIES[type].map((h) => `## ${h}\nBody of ${h}.`).join("\n\n");
    const sections = splitSections(markdown, type);

    expect(sections[0]).toEqual({ heading: null, body: "Intro text.", folded: false });
    expect(sections.slice(1).map((s) => s.heading)).toEqual(BODIES[type]);
    expect(sections.filter((s) => s.folded).map((s) => s.heading)).toEqual(EXPECTED_FOLDED[type]);
    expect(TYPE_LABEL[type]).toBeTruthy();
  });

  it("matches a heading that starts with the section name, like the validator does", () => {
    const [solution] = splitSections("## Solution in Java\nclass A {}", "coding");
    expect(solution.folded).toBe(true);
  });

  it("keeps a body that has no sections at all", () => {
    expect(splitSections("Just a paragraph.", "concept")).toEqual([
      { heading: null, body: "Just a paragraph.", folded: false },
    ]);
  });
});
