import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";
import { isRightNumber, parsePredict, readNumber, type PredictSpec } from "./practicals";

/**
 * "Predict, then reveal": the learner commits an answer before seeing what really happened. The answer
 * locks once checked, because changing it after the reveal would defeat the point. Nothing is saved.
 */
export function Predict({ source, render }: { source: string; render: (markdown: string) => ReactNode }) {
  const parsed = useMemo(() => {
    try { return { spec: parsePredict(source) }; } catch (e) { return { error: (e as Error).message }; }
  }, [source]);
  if ("error" in parsed) return <p className="practical-error">This exercise could not be read: {parsed.error}</p>;
  return <PredictExercise spec={parsed.spec} render={render} />;
}

function PredictExercise({ spec, render }: { spec: PredictSpec; render: (markdown: string) => ReactNode }) {
  const name = useId();
  const questionId = useId();
  const [choice, setChoice] = useState<number | null>(null);
  const [typed, setTyped] = useState("");
  const [checked, setChecked] = useState(false);
  const status = useRef<HTMLParagraphElement>(null);
  // Check disappears once used, so focus moves to the result instead of falling to the page.
  useEffect(() => { if (checked) status.current?.focus(); }, [checked]);
  const value = spec.kind === "number" ? readNumber(typed) : null;
  const ready = spec.kind === "choice" ? choice !== null : value !== null;
  const right = spec.kind === "choice" ? choice === spec.answer : value !== null && isRightNumber(spec, value);
  const answer = spec.kind === "choice" ? spec.choices[spec.answer]
    : `${spec.number}${spec.tolerance ? ` (± ${spec.tolerance})` : ""}${spec.unit ? ` ${spec.unit}` : ""}`;
  const label = `Your answer${spec.kind === "number" && spec.unit ? ` (${spec.unit})` : ""}`;

  return (
    <section className="practical predict" aria-label="Predict">
      <p className="practical-kind">Predict</p>
      <div id={questionId}>{render(spec.question)}</div>
      {spec.kind === "choice" ? (
        <div role="radiogroup" aria-labelledby={questionId} className="practical-choices">
          {spec.choices.map((c, i) => (
            <label key={i}>
              <input type="radio" name={name} value={i} checked={choice === i} disabled={checked}
                onChange={() => setChoice(i)} />
              <span>{c}</span>
            </label>
          ))}
        </div>
      ) : (
        <label className="practical-number">
          <span aria-hidden="true">{label}</span>
          {/* "text", not "decimal": the iOS decimal keypad has no minus key. */}
          <input type="text" inputMode="text" value={typed} disabled={checked} aria-label={label} aria-describedby={questionId}
            onChange={(e) => setTyped(e.target.value)} />
        </label>
      )}
      {!checked && (
        <button type="button" className="secondary" disabled={!ready} onClick={() => setChecked(true)}>Check</button>
      )}
      {/* The live region exists from the start, so screen readers announce the result when it appears. */}
      <p role="status" tabIndex={-1} ref={status} className={checked ? `verdict ${right ? "right" : "wrong"}` : "practical-status"}>
        {checked ? (right ? "Right." : `Not quite. The answer is: ${answer}`) : ""}
      </p>
      {checked && (
        <>
          {render(spec.reveal)}
          {spec.output && <pre className="practical-output"><code>{spec.output}</code></pre>}
        </>
      )}
    </section>
  );
}
