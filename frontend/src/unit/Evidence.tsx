import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";
import { markLines, parseEvidence, type EvidenceSpec } from "./practicals";

/**
 * "Read the evidence": a dump, a log or some code, and the learner selects the lines that cause the
 * problem. Each line is a native checkbox, so the keyboard and screen readers work without extra code.
 * After Check, every line that matters is labelled in words ("cause", "not a cause"), not by colour alone,
 * in a column before the code, so a long line cannot scroll the label out of view.
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
  const status = useRef<HTMLParagraphElement>(null);
  const base = useId();
  // Check disappears once used, so focus moves to the result instead of falling to the page.
  useEffect(() => { if (checked) status.current?.focus(); }, [checked]);
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
      <div id={`${base}-prompt`}>{render(spec.prompt)}</div>
      <ol className="evidence-lines" role="group" aria-labelledby={`${base}-prompt`}>
        {spec.lines.map((line, i) => {
          const n = i + 1;
          const mark = !checked ? null : cause.has(n) ? "cause" : selected.has(n) ? "not a cause" : null;
          return (
            <li key={n} className={selected.has(n) ? "selected" : undefined}>
              <label>
                <input type="checkbox" checked={selected.has(n)} disabled={checked} onChange={() => toggle(n)}
                  aria-label={`Line ${n}: ${line.trim() || "(blank)"}`}
                  aria-describedby={mark ? `${base}-mark-${n}` : undefined} />
                <span className="evidence-n" aria-hidden="true">{n}</span>
                {checked && (mark ? <span className="evidence-mark" id={`${base}-mark-${n}`}>{mark}</span> : <span className="evidence-gap" aria-hidden="true" />)}
                <code>{line || " "}</code>
              </label>
            </li>
          );
        })}
      </ol>
      {!checked && (
        <button type="button" className="secondary" disabled={selected.size === 0} onClick={() => setChecked(true)}>Check</button>
      )}
      {/* The live region exists from the start, so screen readers announce the result when it appears. */}
      <p role="status" tabIndex={-1} ref={status} className={checked ? `verdict ${result.right ? "right" : "wrong"}` : "practical-status"}>
        {checked ? verdict : ""}
      </p>
      {checked && render(spec.explain)}
    </section>
  );
}
