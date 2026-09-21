import { PGlite } from "@electric-sql/pglite";
import type { SqlFixture } from "../api";
import { verdict, type Row, type Verdict } from "./compare";

export type Outcome = { columns: string[]; rows: Row[]; verdict: Verdict };

export type Session = { run(query: string): Promise<Outcome>; close(): Promise<void> };

/**
 * A SQL problem's dataset in PGlite, a real PostgreSQL compiled to WebAssembly, running in the
 * browser: no SQL reaches the server. The right answer is worked out the same way, by running the
 * unit's reference query, so there is no stored answer to go stale.
 *
 * Each run happens inside a transaction that is rolled back, so a DELETE or UPDATE cannot spoil the
 * data for the next attempt. A learner who types COMMIT themselves can; closing and opening a new
 * session starts again.
 */
export async function openSession(fixture: SqlFixture): Promise<Session> {
  const db = await PGlite.create();
  await db.exec(fixture.schema);
  await db.exec(fixture.seed);
  const answer = (await db.query<Row>(fixture.reference, [], { rowMode: "array" })).rows;

  return {
    async run(query) {
      await db.exec("begin");
      try {
        const results = await db.exec(query, { rowMode: "array" });
        // With several statements, the last one that returns rows is the answer.
        const last = results.filter((r) => r.fields.length > 0).at(-1);
        const rows = (last?.rows ?? []) as Row[];
        return { columns: last?.fields.map((f) => f.name) ?? [], rows, verdict: verdict(answer, rows, fixture.orderMatters) };
      } finally {
        await db.exec("rollback");
      }
    },
    close: () => db.close(),
  };
}
