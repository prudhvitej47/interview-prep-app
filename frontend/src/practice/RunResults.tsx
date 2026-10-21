import { Fragment } from "react";
import type { CaseResult, Refusal, RunAnswer } from "../api";

const REFUSALS: Record<Refusal, string> = {
  busy: "The code runner is busy with another run. Try again in a few seconds.",
  unavailable: "The code runner isn't answering. Your code is saved; you can check it by hand below.",
  "not-ready": "This problem can't run here yet. Your code is saved.",
  "one-at-a-time": "Your previous run is still going.",
  "too-many": "That's ten runs in a minute. Wait a moment and try again.",
  "too-big": "Code is limited to 32 KB.",
  "runner-refused": "The runner could not use this problem's cases: a problem in the curriculum, not in your code. Your code is saved.",
  "signed-out": "Reload the page to sign in again. Copy your code first if you changed it since saving.",
  gone: "This problem is no longer available.",
  "server-error": "Something went wrong on the server. Try again in a moment.",
};

const STOPPED: Record<string, string> = {
  "compile-error": "Does not compile.",
  "signature-mismatch": "The method doesn't match the problem. Keep the class, method name and parameter types from the starting code.",
  timeout: "Took too long and was stopped. Look for a loop that never ends, or a slower approach than the problem needs.",
  oom: "Ran out of memory and was stopped.",
  crashed: "The program crashed before it finished.",
};

// The symbol says pass or not at a glance; the word says how it failed. Only the symbol takes the state
// colour: green is 3.2:1 on the light background, enough for a bold symbol but not for small text.
const MARK: Record<CaseResult["verdict"], { glyph: string; word?: string }> = {
  pass: { glyph: "✓" },
  fail: { glyph: "✗" },
  error: { glyph: "✗", word: "error" },
  timeout: { glyph: "✗", word: "timed out" },
  "not-run": { glyph: "–", word: "not run" },
};

function Mark({ verdict }: { verdict: CaseResult["verdict"] }) {
  const m = MARK[verdict];
  return (
    <>
      <span className={`glyph ${verdict === "pass" ? "pass" : verdict === "not-run" ? "" : "fail"}`}>{m.glyph}</span>
      {m.word && <> {m.word}</>}
    </>
  );
}

/**
 * What a Run or Submit came back with (runner design §2): one verdict line, the visible cases with
 * what the code returned, and on Submit a single line for the hidden cases. Compiler text sits in a
 * plain block. Printed output only ever arrives on Run.
 */
export function RunResults({ answer, submit }: { answer: RunAnswer; submit: boolean }) {
  if (answer.kind === "refused") return <p role="status" className="verdict">{REFUSALS[answer.reason]}</p>;
  const r = answer.result;
  const passed = r.cases.filter((c) => c.verdict === "pass").length + (r.hidden?.passed ?? 0);
  const total = r.cases.length + (r.hidden?.total ?? 0);
  const allPass = r.status === "finished" && passed === total;
  const line = r.status === "finished"
    ? (allPass ? `✓ All ${total} cases pass.` : `${passed} of ${total} pass.`)
    : STOPPED[r.status] ?? "The run stopped.";
  return (
    <div className="run-results">
      <p role="status" className={`verdict ${allPass ? "right" : "wrong"}`}>{line}</p>
      {r.compileErrors && <pre className="compile-errors">{r.compileErrors}</pre>}
      {r.cases.length > 0 && r.status !== "compile-error" && (
        <div className="result">
          <table aria-label="Results">
            <thead>
              <tr><th>Result</th><th>Case</th><th>Input</th><th>Expected</th><th>Got</th></tr>
            </thead>
            <tbody>
              {r.cases.map((c, i) => (
                <Fragment key={i}>
                  <tr>
                    <td className="mark"><Mark verdict={c.verdict} /></td>
                    <td>{c.name}</td>
                    <td><code>{c.input}</code></td>
                    <td><code>{c.expected}</code></td>
                    <td><code>{c.got ?? ""}</code></td>
                  </tr>
                  {c.error && (
                    <tr className="case-error">
                      <td colSpan={5}><code>throws {c.error}</code></td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {r.cases.some((c) => c.verdict === "fail" && c.got !== null && c.got === c.expected) && (
        <p className="hint">
          A result that looks the same as expected but did not pass is usually a different type: check the
          method's return type against the starting code.
        </p>
      )}
      {submit && r.hidden && <p className="hidden-count">Hidden cases: {r.hidden.passed} of {r.hidden.total} pass</p>}
      {!submit && r.output && (
        <>
          <h4>Printed</h4>
          <pre className="printed">{r.output}</pre>
        </>
      )}
    </div>
  );
}
