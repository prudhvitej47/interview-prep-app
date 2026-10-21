import { lazy, Suspense, useEffect, useState } from "react";
import { CodeTooBigError, fetchCode, runCode, saveCode, type LastSubmit, type Refusal, type RunAnswer, type TestCase } from "../api";
import { useUnsavedGuard } from "../unit/useUnsavedGuard";
import { CodingPractice } from "./CodingPractice";
import { PlainEditor } from "./PlainEditor";
import { RunResults } from "./RunResults";
import { storeChoice, storedChoice, type EditorChoice } from "./editorChoice";

const CodeMirrorEditor = lazy(() => import("./CodeMirrorEditor"));

const LABEL = "Your solution";

// Refusals that come after the server saved the code (PracticeController saves first); too-big, a lost
// sign-in, a missing unit or a server error may not have saved it.
const SAVED_BEFORE = new Set<Refusal>(["busy", "unavailable", "not-ready", "one-at-a-time", "too-many", "runner-refused"]);

function shortDate(iso: string) {
  return new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short" });
}

/**
 * Write, save, run and submit a coding problem (runner design §2). The code is saved with every run.
 * The self-check table stays for when the runner cannot help: before this problem's cases reach the
 * server, or when the runner is down. Nothing here marks the unit done.
 */
export function CodeRunner({ unitId, cases, hidden }: { unitId: string; cases: TestCase[]; hidden: number }) {
  const [code, setCode] = useState("");
  const [savedCode, setSavedCode] = useState("");
  const [savedAt, setSavedAt] = useState<string | null>(null);
  const [lastSubmit, setLastSubmit] = useState<LastSubmit | null>(null);
  const [canRun, setCanRun] = useState(false);
  const [load, setLoad] = useState<"loading" | "failed" | "ready">("loading");
  const [attempt, setAttempt] = useState(0);
  const [busy, setBusy] = useState<null | "saving" | "running" | "submitting">(null);
  const [answer, setAnswer] = useState<{ answer: RunAnswer; submit: boolean } | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [choice, setChoice] = useState<EditorChoice>(storedChoice);

  useEffect(() => {
    let current = true;
    setLoad("loading");
    fetchCode(unitId)
      .then((d) => {
        if (!current) return;
        const shown = d.code || d.starter || "";
        setCode(shown);
        setSavedCode(shown);
        setSavedAt(d.savedAt);
        setLastSubmit(d.lastSubmit);
        setCanRun(d.starter !== null);
        setLoad("ready");
      })
      .catch(() => {
        if (current) setLoad("failed");
      });
    return () => {
      current = false;
    };
  }, [unitId, attempt]);

  const dirty = code !== savedCode;
  useUnsavedGuard(dirty, "Your code has unsaved changes. Leave without saving them?");
  const locked = load !== "ready";

  async function save() {
    setBusy("saving");
    setProblem(null);
    try {
      const d = await saveCode(unitId, code);
      setSavedCode(d.code);
      setSavedAt(d.savedAt);
    } catch (e) {
      setProblem(e instanceof CodeTooBigError ? "Code is limited to 32 KB." : "Could not save — try again.");
    } finally {
      setBusy(null);
    }
  }

  async function run(submit: boolean) {
    const sent = code;
    setBusy(submit ? "submitting" : "running");
    setProblem(null);
    setAnswer(null);
    try {
      const a = await runCode(unitId, sent, submit);
      if (a.kind === "result" || SAVED_BEFORE.has(a.reason)) {
        setSavedCode(sent); // the server saved it before running or refusing
        setSavedAt(new Date().toISOString());
      }
      if (a.kind === "result" && submit) {
        const r = a.result;
        setLastSubmit({
          passed: r.cases.filter((c) => c.verdict === "pass").length + (r.hidden?.passed ?? 0),
          total: r.cases.length + (r.hidden?.total ?? 0),
          at: new Date().toISOString(),
        });
      }
      setAnswer({ answer: a, submit });
    } catch {
      setProblem("Could not reach the app — try again.");
    } finally {
      setBusy(null);
    }
  }

  function pick(next: EditorChoice) {
    setChoice(next);
    storeChoice(next);
  }

  const cannotRun = load === "ready" && !canRun ? `${unitId}-cannot-run` : undefined;
  const editorProps = { value: code, onChange: setCode, label: LABEL, disabled: locked };
  const runnerDown = answer?.answer.kind === "refused" && answer.answer.reason === "unavailable";
  const status = load === "failed"
    ? "Could not load your code."
    : problem ?? (busy === "running" ? "Running…" : busy === "submitting" ? "Submitting…" : busy === "saving" ? "Saving…"
      : dirty ? "Unsaved changes" : savedAt ? `Saved ${new Date(savedAt).toLocaleString()}` : "");

  return (
    <section className="practice code-practice">
      <h3>Write and run your solution</h3>
      <p className="hint">
        Java 25.{" "}
        {canRun && (
          <>
            Run tries the cases below
            {hidden > 0 ? `; Submit also tries ${hidden} hidden case${hidden === 1 ? "" : "s"}` : "; Submit tries them too"}.
            Neither marks the unit done.{" "}
          </>
        )}
        Tab indents; press Esc, then Tab, to move on.
      </p>
      {load === "ready" && !canRun && (
        <p className="hint" id={`${unitId}-cannot-run`}>This problem can't run here yet: it arrives with the next curriculum update. Save your code now, and check it by hand below.</p>
      )}
      <label className="editor-choice">
        <input type="checkbox" checked={choice === "codemirror"} onChange={(e) => pick(e.target.checked ? "codemirror" : "plain")} />
        {" "}Highlight syntax
      </label>
      {choice === "codemirror" ? (
        <Suspense fallback={<PlainEditor {...editorProps} />}>
          <CodeMirrorEditor {...editorProps} />
        </Suspense>
      ) : (
        <PlainEditor {...editorProps} />
      )}
      <div className="note-actions">
        {load === "failed" ? (
          <button type="button" className="secondary" onClick={() => setAttempt((n) => n + 1)}>Try loading it again</button>
        ) : null}
        <button type="button" className="secondary" onClick={() => run(false)} disabled={locked || !canRun || busy !== null} aria-describedby={cannotRun}>Run</button>
        <button type="button" className="secondary" onClick={() => run(true)} disabled={locked || !canRun || busy !== null} aria-describedby={cannotRun}>Submit</button>
        <button type="button" className="secondary" onClick={save} disabled={locked || !dirty || busy !== null}>Save</button>
        <span role="status">{status}</span>
      </div>
      {lastSubmit && (
        <p className="hint">Last submit: {lastSubmit.passed} of {lastSubmit.total} pass, {shortDate(lastSubmit.at)}</p>
      )}
      {answer && <RunResults answer={answer.answer} submit={answer.submit} />}
      {(load === "ready" && !canRun) || runnerDown ? <CodingPractice cases={cases} hidden={hidden} /> : null}
    </section>
  );
}
