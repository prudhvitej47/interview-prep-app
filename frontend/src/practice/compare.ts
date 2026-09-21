/**
 * Whether a learner's rows are the answer. The same rules as the content repository's check_sql.mjs,
 * so what CI accepts as the unit's expected output is what the page accepts from the learner:
 *
 * - rows are compared by position and value, not column name, so `as merchant` is optional;
 * - numbers compare as numbers, so 12.5 and "12.50" are the same answer;
 * - order counts only when the problem asks for it.
 */
export type Row = unknown[];

export type Verdict =
  | { kind: "right" }
  | { kind: "wrong-order" }
  | { kind: "row-count"; got: number; want: number }
  | { kind: "different" };

export const cell = (v: unknown): string =>
  v === null || v === undefined ? "NULL" : v instanceof Date ? v.toISOString() : String(v).trim();

function same(a: unknown, b: unknown): boolean {
  const x = cell(a);
  const y = cell(b);
  return x === y || (x !== "" && y !== "" && !isNaN(Number(x)) && !isNaN(Number(y)) && Number(x) === Number(y));
}

const sameRow = (a: Row, b: Row) => a.length === b.length && a.every((v, i) => same(v, b[i]));

function sameAsBag(expected: Row[], actual: Row[]): boolean {
  const left = [...actual];
  return expected.every((row) => {
    const i = left.findIndex((r) => sameRow(row, r));
    if (i < 0) return false;
    left.splice(i, 1);
    return true;
  });
}

export function verdict(expected: Row[], actual: Row[], orderMatters: boolean): Verdict {
  if (expected.length !== actual.length) return { kind: "row-count", got: actual.length, want: expected.length };
  if (!sameAsBag(expected, actual)) return { kind: "different" };
  if (orderMatters && !expected.every((row, i) => sameRow(row, actual[i]))) return { kind: "wrong-order" };
  return { kind: "right" };
}
