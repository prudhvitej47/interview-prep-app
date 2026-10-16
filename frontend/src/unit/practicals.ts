/**
 * The two exercise fences a unit can hold, and how each is checked. The same rules live in the content
 * repository's scripts/check_practicals.mjs, which rejects a broken fence in CI: change both together.
 *
 * A ```predict fence asks for an answer before it shows what really happened. A ```evidence fence shows
 * a dump, a log or some code and asks which lines cause the problem. Both are JSON, like a sketch.
 * Nothing is saved: these are for thinking, not for progress.
 */

export type PredictSpec =
  | { kind: "choice"; question: string; choices: string[]; answer: number; reveal: string; output?: string }
  | { kind: "number"; question: string; number: number; tolerance: number; unit?: string; reveal: string; output?: string };

export interface EvidenceSpec { prompt: string; lines: string[]; cause: number[]; explain: string }

const PREDICT_FIELDS = new Set(["question", "choices", "answer", "number", "tolerance", "unit", "reveal", "output"]);
const EVIDENCE_FIELDS = new Set(["prompt", "lines", "cause", "explain"]);

function object(source: string, fields: Set<string>): Record<string, unknown> {
  let value: unknown;
  try {
    value = JSON.parse(source);
  } catch {
    throw new Error("the fence is not valid JSON");
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new Error("the fence must be a JSON object");
  for (const key of Object.keys(value)) if (!fields.has(key)) throw new Error(`unknown field ${key}`);
  return value as Record<string, unknown>;
}

/** A text field: one string, or an array of strings that reads better in review, joined by newlines. */
function text(value: unknown, name: string, required: true): string;
function text(value: unknown, name: string, required: false): string | undefined;
function text(value: unknown, name: string, required: boolean): string | undefined {
  if (value === undefined && !required) return undefined;
  const joined = typeof value === "string" ? value
    : Array.isArray(value) && value.every((v) => typeof v === "string") ? value.join("\n") : null;
  if (joined === null || !joined.trim()) throw new Error(`${name} must be text`);
  return joined;
}

export function parsePredict(source: string): PredictSpec {
  const o = object(source, PREDICT_FIELDS);
  const question = text(o.question, "question", true);
  const reveal = text(o.reveal, "reveal", true);
  const output = text(o.output, "output", false);
  const hasChoices = o.choices !== undefined;
  const hasNumber = o.number !== undefined;
  if (hasChoices === hasNumber) throw new Error("give either choices and answer, or number");
  const extra = output === undefined ? {} : { output };
  if (hasChoices) {
    const choices = o.choices;
    if (!Array.isArray(choices) || choices.length < 2 || choices.length > 6
      || !choices.every((c) => typeof c === "string" && c.trim()))
      throw new Error("choices must be 2 to 6 pieces of text");
    const answer = o.answer;
    if (typeof answer !== "number" || !Number.isInteger(answer) || answer < 0 || answer >= choices.length)
      throw new Error("answer must be the index of one of the choices, counting from 0");
    return { kind: "choice", question, choices: choices as string[], answer, reveal, ...extra };
  }
  if (typeof o.number !== "number" || !Number.isFinite(o.number)) throw new Error("number must be a number");
  const tolerance = o.tolerance ?? 0;
  if (typeof tolerance !== "number" || !(tolerance >= 0)) throw new Error("tolerance must be 0 or more");
  if (o.unit !== undefined && (typeof o.unit !== "string" || !o.unit.trim())) throw new Error("unit must be text");
  const unit = typeof o.unit === "string" ? { unit: o.unit } : {};
  return { kind: "number", question, number: o.number, tolerance, ...unit, reveal, ...extra };
}

/** What a learner typed, read leniently: spaces and thousands commas are ignored. */
export function readNumber(raw: string): number | null {
  const cleaned = raw.trim().replaceAll(",", "");
  if (!/^-?\d+(\.\d+)?$/.test(cleaned)) return null;
  return Number(cleaned);
}

export function isRightNumber(spec: Extract<PredictSpec, { kind: "number" }>, value: number): boolean {
  return Math.abs(value - spec.number) <= spec.tolerance;
}

export function parseEvidence(source: string): EvidenceSpec {
  const o = object(source, EVIDENCE_FIELDS);
  const prompt = text(o.prompt, "prompt", true);
  const explain = text(o.explain, "explain", true);
  const lines = o.lines;
  if (!Array.isArray(lines) || lines.length === 0 || !lines.every((l) => typeof l === "string"))
    throw new Error("lines must be a non-empty array of text");
  const cause = o.cause;
  if (!Array.isArray(cause) || cause.length === 0) throw new Error("cause must list at least one line number");
  const seen = new Set<number>();
  for (const n of cause) {
    if (typeof n !== "number" || !Number.isInteger(n) || n < 1 || n > lines.length)
      throw new Error(`cause names line ${String(n)}, which is not in lines`);
    if (seen.has(n)) throw new Error(`cause names line ${n} twice`);
    seen.add(n);
  }
  return { prompt, lines: lines as string[], cause: [...seen].sort((a, b) => a - b), explain };
}

export function markLines(spec: EvidenceSpec, selected: number[]): { right: boolean; missed: number[]; extra: number[] } {
  const chosen = new Set(selected);
  const cause = new Set(spec.cause);
  const missed = spec.cause.filter((n) => !chosen.has(n));
  const extra = [...chosen].filter((n) => !cause.has(n)).sort((a, b) => a - b);
  return { right: missed.length === 0 && extra.length === 0, missed, extra };
}
