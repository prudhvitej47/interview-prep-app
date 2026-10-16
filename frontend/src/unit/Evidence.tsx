import { useMemo, useState, type ReactNode } from "react";
import { markLines, parseEvidence, type EvidenceSpec } from "./practicals";

/**
 * "Read the evidence": a dump, a log or some code, and the learner selects the lines that cause the
 * problem. Each line is a native checkbox, so the keyboard and screen readers work without extra code.
 * After Check, every line that matters is labelled in words ("cause", "not a cause"), not by colour alone.
 */
export function Evidence({ source, render }: { source: string; render: (markdown: string) => ReactNode }) {
  const parsed = useMemo(() => {
    try { return { spec: parseEvidence(source) }; } catch (e) { return { error: (e as Error).message }; }
  }, [source]);
  if ("error" in parsed) return <p className="practical-error">This exercise could not be read: {parsed.error}</p>;
  return <EvidenceExercise spec={parsed.spec} render={render} />;
}

function EvidenceExercise({ spec, render }: { spec: EvidenceSpec; render: (markdown: string) => ReactNode }) {
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [checked, setChecked] = useState(false);
  const result = markLines(spec, [...selected]);
  const cause = new Set(spec.cause);

  function toggle(n: number) {
    const next = new Set(selected);
    if (next.has(n)) next.delete(n);
    else next.add(n);
    setSelected(next);
  }

  const list = (ns: number[]) => ns.map((n) => `line ${n}`).join(", ");
  const verdict = result.right ? "Right." : [
    "Not quite.",
    result.missed.length ? `Missed: ${list(result.missed)}.` : "",
    result.extra.length ? `Not a cause: ${list(result.extra)}.` : "",
  ].filter(Boolean).join(" ");

  return (
    <section className="practical evidence" aria-label="Read the evidence">
      <p className="practical-kind">Read the evidence</p>
      {render(spec.prompt)}
      <ol className="evidence-lines">
        {spec.lines.map((line, i) => {
          const n = i + 1;
          const mark = !checked ? null : cause.has(n) ? "cause" : selected.has(n) ? "not a cause" : null;
          return (
            <li key={n} className={selected.has(n) ? "selected" : undefined}>
              <label>
                <input type="checkbox" checked={selected.has(n)} disabled={checked} onChange={() => toggle(n)}
                  aria-label={`Line ${n}: ${line.trim() || "(blank)"}`} />
                <span className="evidence-n" aria-hidden="true">{n}</span>
                <code>{line || " "}</code>
                {mark && <span className="evidence-mark">{mark}</span>}
              </label>
            </li>
          );
        })}
      </ol>
      {!checked && (
        <button type="button" className="secondary" disabled={selected.size === 0} onClick={() => setChecked(true)}>Check</button>
      )}
      {checked && (
        <>
          <p role="status" className={`verdict ${result.right ? "right" : "wrong"}`}>{verdict}</p>
          {render(spec.explain)}
        </>
      )}
    </section>
  );
}
