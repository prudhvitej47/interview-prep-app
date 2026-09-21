import { useRef, useState } from "react";
import type { SqlFixture } from "../api";
import { cell, type Verdict } from "./compare";
import type { Outcome, Session } from "./sqlSession";

const SHOWN_ROWS = 200;

/**
 * The practice box under a SQL problem. PGlite is several megabytes, so it loads on the first Run,
 * not with the page: sqlSession, which imports it, is itself imported only then.
 */
export function SqlPractice({ fixture }: { fixture: SqlFixture }) {
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState<"idle" | "starting" | "running">("idle");
  const [result, setResult] = useState<Outcome | null>(null);
  const [error, setError] = useState<string | null>(null);
  const session = useRef<Promise<Session> | null>(null);

  async function run() {
    setError(null);
    setStatus(session.current ? "running" : "starting");
    try {
      session.current ??= import("./sqlSession").then((m) => m.openSession(fixture));
      const open = await session.current;
      setStatus("running");
      setResult(await open.run(query));
    } catch (e) {
      setResult(null);
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setStatus("idle");
    }
  }

  async function reset() {
    const current = session.current;
    session.current = null;
    setResult(null);
    setError(null);
    if (current) await (await current).close();
  }

  return (
    <section className="practice">
      <h3>Try it</h3>
      <p className="hint">
        Runs in your browser against the tables above. Nothing you run changes the data for your next try.
      </p>
      <textarea
        aria-label="Your query"
        rows={8}
        spellCheck={false}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && (e.metaKey || e.ctrlKey) && query.trim()) void run();
        }}
        placeholder="select …"
      />
      <div className="note-actions">
        <button onClick={run} disabled={status !== "idle" || !query.trim()}>
          {status === "starting" ? "Starting Postgres…" : status === "running" ? "Running…" : "Run"}
        </button>
        <button className="secondary" onClick={reset} disabled={status !== "idle"}>Reset data</button>
        <span role="status">{status === "idle" && "⌘/Ctrl + Enter runs it"}</span>
      </div>

      {error && <p role="alert" className="verdict wrong">{error}</p>}
      {result && (
        <p role="status" className={`verdict ${result.verdict.kind === "right" ? "right" : "wrong"}`}>
          {describe(result.verdict)}
        </p>
      )}
      {result && (
        <div className="result">
          <table>
            <thead>
              <tr>{result.columns.map((c, i) => <th key={i}>{c}</th>)}</tr>
            </thead>
            <tbody>
              {result.rows.slice(0, SHOWN_ROWS).map((row, i) => (
                <tr key={i}>{row.map((v, j) => <td key={j}>{cell(v)}</td>)}</tr>
              ))}
            </tbody>
          </table>
          {result.rows.length > SHOWN_ROWS && <p className="hint">Showing {SHOWN_ROWS} of {result.rows.length} rows.</p>}
        </div>
      )}
    </section>
  );
}

function describe(v: Verdict): string {
  switch (v.kind) {
    case "right":
      return "✓ That's the answer.";
    case "wrong-order":
      return "The right rows, in the wrong order. Check the question's ordering.";
    case "row-count":
      return `Not yet: your query returns ${v.got} row${v.got === 1 ? "" : "s"}, the answer has ${v.want}.`;
    case "different":
      return "Not yet: the right number of rows, but some values or columns differ.";
  }
}
