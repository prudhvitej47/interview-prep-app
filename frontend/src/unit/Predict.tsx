import { useId, useMemo, useState, type ReactNode } from "react";
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
  const [choice, setChoice] = useState<number | null>(null);
  const [typed, setTyped] = useState("");
  const [checked, setChecked] = useState(false);
  const value = spec.kind === "number" ? readNumber(typed) : null;
  const ready = spec.kind === "choice" ? choice !== null : value !== null;
  const right = spec.kind === "choice" ? choice === spec.answer : value !== null && isRightNumber(spec, value);
  const answer = spec.kind === "choice" ? spec.choices[spec.answer]
    : `${spec.number}${spec.tolerance ? ` (± ${spec.tolerance})` : ""}${spec.unit ? ` ${spec.unit}` : ""}`;
  const label = `Your answer${spec.kind === "number" && spec.unit ? ` (${spec.unit})` : ""}`;

  return (
    <section className="practical predict" aria-label="Predict">
      <p className="practical-kind">Predict</p>
      {render(spec.question)}
      {spec.kind === "choice" ? (
        <div role="radiogroup" aria-label="Your answer" className="practical-choices">
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
          <input type="text" inputMode="decimal" value={typed} disabled={checked} aria-label={label}
            onChange={(e) => setTyped(e.target.value)} />
        </label>
      )}
      {!checked && (
        <button type="button" className="secondary" disabled={!ready} onClick={() => setChecked(true)}>Check</button>
      )}
      {checked && (
        <>
          <p role="status" className={`verdict ${right ? "right" : "wrong"}`}>
            {right ? "Right." : `Not quite. The answer is: ${answer}`}
          </p>
          {render(spec.reveal)}
          {spec.output && <pre className="practical-output"><code>{spec.output}</code></pre>}
        </>
      )}
    </section>
  );
}
