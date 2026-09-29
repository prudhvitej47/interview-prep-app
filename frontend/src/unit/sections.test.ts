import { describe, expect, it } from "vitest";
import { splitFolds, splitSections, TYPE_LABEL } from "./sections";

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

describe("deep dives and pause-and-think answers", () => {
  it("folds a deep dive until the next ### heading, which renders as before", () => {
    const body = [
      "A lighthouse keeper logs every lamp change.",
      "### Deep dive: How the log survives a storm",
      "The log is copied to the mainland each night.",
      "",
      "#### A smaller heading stays inside",
      "### Keeping watch",
      "The keeper checks the lamp hourly.",
    ].join("\n");
    expect(splitFolds(body)).toEqual([
      { kind: "text", body: "A lighthouse keeper logs every lamp change." },
      {
        kind: "fold",
        summary: "Deep dive: How the log survives a storm",
        body: "The log is copied to the mainland each night.\n\n#### A smaller heading stays inside",
      },
      { kind: "text", body: "### Keeping watch\nThe keeper checks the lamp hourly." },
    ]);
  });

  it("ends a deep dive at the next ## section", () => {
    const [how, check] = splitSections(
      "## How it works\nLamps rotate.\n### Deep dive: Gear ratios\nSeven teeth to one.\n## Check yourself\nWhy rotate?",
      "concept",
    );
    expect(splitFolds(how.body)).toEqual([
      { kind: "text", body: "Lamps rotate." },
      { kind: "fold", summary: "Deep dive: Gear ratios", body: "Seven teeth to one." },
    ]);
    expect(check.heading).toBe("Check yourself");
    expect(splitFolds(check.body)).toEqual([{ kind: "text", body: "Why rotate?" }]);
  });

  it("keeps the pause-and-think question visible and folds its answer, with or without a label", () => {
    const body = [
      "> **Pause and think.** Why does the lamp turn instead of shining everywhere at once?",
      "",
      "### Answer",
      "A turning beam is brighter and tells ships which lighthouse it is.",
      "",
      "> **Pause and think.** What if the motor stops?",
      "### answer: when the motor fails",
      "The keeper turns it by hand.",
    ].join("\n");
    expect(splitFolds(body)).toEqual([
      { kind: "text", body: "> **Pause and think.** Why does the lamp turn instead of shining everywhere at once?" },
      { kind: "fold", summary: "Answer", body: "A turning beam is brighter and tells ships which lighthouse it is." },
      { kind: "text", body: "> **Pause and think.** What if the motor stops?" },
      { kind: "fold", summary: "Answer: when the motor fails", body: "The keeper turns it by hand." },
    ]);
  });

  it("matches the keyword in any case and leaves other ### headings alone", () => {
    expect(splitFolds("### DEEP DIVE: Lens grinding\nSlowly.")).toEqual([
      { kind: "fold", summary: "Deep dive: Lens grinding", body: "Slowly." },
    ]);
    for (const heading of ["### Answers from past keepers", "### Deep dive", "### The deep dive: not at the start"]) {
      expect(splitFolds(`${heading}\nText.`)).toEqual([{ kind: "text", body: `${heading}\nText.` }]);
    }
  });

  it("does not treat a ### line inside a code fence as a heading", () => {
    const body = "### Deep dive: Config\n```yaml\n### Answer\nlamp: on\n```\nAfter the fence.";
    expect(splitFolds(body)).toEqual([
      { kind: "fold", summary: "Deep dive: Config", body: "```yaml\n### Answer\nlamp: on\n```\nAfter the fence." },
    ]);
  });

  it("works inside a section that is itself folded", () => {
    const [problem, solution] = splitSections(
      "## Problem\nCount the ships.\n## Solution\nUse a set.\n### Deep dive: Why not a list\nDuplicates.",
      "coding",
    );
    expect(problem.folded).toBe(false);
    expect(solution.folded).toBe(true);
    expect(splitFolds(solution.body)).toEqual([
      { kind: "text", body: "Use a set." },
      { kind: "fold", summary: "Deep dive: Why not a list", body: "Duplicates." },
    ]);
  });
});
