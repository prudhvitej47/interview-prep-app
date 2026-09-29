/**
 * What makes each unit type look different on the page.
 *
 * Every type is the same Markdown underneath, with the sections the content validator requires for
 * it. What changes is which of those sections would spoil an attempt: those start folded, and open
 * when the learner asks. Headings match the way validate.py matches them — equal, or starting with
 * the name — so "## Solution in Java" still counts as the solution.
 */
export const TYPE_LABEL: Record<string, string> = {
  concept: "Concept",
  question: "Question",
  coding: "Coding problem",
  sql: "SQL problem",
  lld: "Low-level design",
  hld: "System design",
  scenario: "Scenario",
  project: "Project deep dive",
  behavioral: "Behavioural",
};

const FOLDED: Record<string, string[]> = {
  coding: ["hints", "approach", "solution", "complexity"],
  sql: ["solution"],
  scenario: ["expected diagnosis", "remediation"],
  question: ["key points", "rubric", "model answer"],
  // System and object design stay open for now: they are studied as worked cases first. Practising
  // them from memory comes with reviews, where the planner asks for an outline before showing one.
};

export type Section = { heading: string | null; body: string; folded: boolean };

export function splitSections(markdown: string, type: string): Section[] {
  const folded = FOLDED[type] ?? [];
  const parts = markdown.split(/^## /m);
  const sections: Section[] = [];
  const intro = parts.shift()?.trim();
  if (intro) sections.push({ heading: null, body: intro, folded: false });
  for (const part of parts) {
    const newline = part.indexOf("\n");
    const heading = (newline === -1 ? part : part.slice(0, newline)).trim();
    const body = newline === -1 ? "" : part.slice(newline + 1).trim();
    const key = heading.toLowerCase();
    sections.push({ heading, body, folded: folded.some((f) => key === f || key.startsWith(f)) });
  }
  return sections;
}

/**
 * A piece of a section's body: plain Markdown, or a block the reader opens when they want it.
 *
 * Two conventions fold inside any section of any unit type:
 * - `### Deep dive: <title>` holds internals, version specifics and edge cases that would break the
 *   reading flow. Closed until opened; the summary is the heading itself.
 * - `### Answer` (or `### Answer: <label>`) follows a `> **Pause and think.**` question. The question
 *   stays visible; only the answer folds.
 * A fold runs to the next `###` heading, the next `> **Pause and think.**` question (so a second
 * question after an answer is never hidden inside it), or the end of the section (the `##` split
 * ends it). Lines inside a code fence are code, not headings. Any other `###` heading renders as
 * before.
 */
export type Block = { kind: "text"; body: string } | { kind: "fold"; summary: string; body: string };

const FOLD_HEADING = /^###[ \t]+(deep dive|answer)[ \t]*(?::[ \t]*(.*?))?[ \t#]*$/i;
const ANY_H3 = /^###[ \t]/;
const FENCE = /^ {0,3}(`{3,}|~{3,})/;
const PAUSE = /^ {0,3}>[ \t]*\*\*pause and think\b/i;

export function splitFolds(body: string): Block[] {
  const blocks: Block[] = [];
  let lines: string[] = [];
  let fold: string | null = null;
  let fence: string | null = null;

  const flush = () => {
    const text = lines.join("\n").trim();
    if (fold !== null) blocks.push({ kind: "fold", summary: fold, body: text });
    else if (text) blocks.push({ kind: "text", body: text });
    lines = [];
  };

  for (const line of body.split("\n")) {
    const opens = FENCE.exec(line);
    if (fence !== null) {
      if (opens && opens[1][0] === fence[0] && opens[1].length >= fence.length && line.trim() === opens[1]) {
        fence = null;
      }
    } else if (opens) {
      fence = opens[1];
    } else if (fold !== null && PAUSE.test(line)) {
      flush();
      fold = null;
    } else if (ANY_H3.test(line)) {
      const match = FOLD_HEADING.exec(line);
      const opensFold = match ? summaryOf(match[1], match[2]) : null;
      if (opensFold !== null || fold !== null) {
        flush();
        fold = opensFold;
        if (fold !== null) continue;
      }
    }
    lines.push(line);
  }
  flush();
  return blocks;
}

/** The summary reads as the heading, with the keyword's case made regular: "Deep dive: Title". */
function summaryOf(keyword: string, label: string | undefined): string | null {
  const name = keyword.toLowerCase() === "answer" ? "Answer" : "Deep dive";
  if (label) return `${name}: ${label}`;
  // A deep dive needs its title; "### Deep dive" alone is an ordinary heading.
  return name === "Answer" ? name : null;
}
