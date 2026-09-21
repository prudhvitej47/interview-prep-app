import { useState } from "react";
import type { TestCase } from "../api";

/**
 * Self-check for a coding problem: the learner runs their own solution and ticks the cases it gets
 * right. Running Java in the app arrives with the code runner in phase 2; until then the hidden
 * cases stay on the server for it, and nothing here is saved.
 */
export function CodingPractice({ cases, hidden }: { cases: TestCase[]; hidden: number }) {
  const [passed, setPassed] = useState<Set<number>>(new Set());

  function toggle(i: number) {
    const next = new Set(passed);
    if (next.has(i)) next.delete(i);
    else next.add(i);
    setPassed(next);
  }

  return (
    <section className="practice">
      <h3>Check your solution</h3>
      <p className="hint">
        Run your solution on each case and tick the ones it gets right.
        {hidden > 0 && <> {hidden} more case{hidden === 1 ? " is" : "s are"} kept back for the code runner.</>}
      </p>
      <table>
        <thead>
          <tr><th>Case</th><th>Input</th><th>Expected</th><th>Passes</th></tr>
        </thead>
        <tbody>
          {cases.map((c, i) => (
            <tr key={i}>
              <td>{c.name}</td>
              <td><code>{c.input}</code></td>
              <td><code>{c.expected}</code></td>
              <td>
                <input type="checkbox" aria-label={`Passes: ${c.name}`} checked={passed.has(i)} onChange={() => toggle(i)} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p role="status" className={`verdict ${passed.size === cases.length ? "right" : ""}`}>
        {passed.size === cases.length ? "✓ Every case passes." : `${passed.size} of ${cases.length} pass.`}
      </p>
    </section>
  );
}
