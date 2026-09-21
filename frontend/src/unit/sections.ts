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
