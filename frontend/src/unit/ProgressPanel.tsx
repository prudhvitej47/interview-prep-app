import { useEffect, useState } from "react";
import { fetchProgress, recordAttempt, undoAttempt, type Progress, type Rating } from "../api";

const RATINGS: { rating: Rating; label: string; hint: string }[] = [
  { rating: "again", label: "Again", hint: "I couldn't do it; show it again tomorrow" },
  { rating: "hard", label: "Hard", hint: "Got there, with effort or a peek" },
  { rating: "good", label: "Good", hint: "Did it, with some thought" },
  { rating: "easy", label: "Easy", hint: "Straightforward" },
];

// What a review means differs by type (proposal F6): re-solve, re-write, or outline from memory.
const HOW_TO_REVIEW: Record<string, string> = {
  coding: "Solve it again without notes, then compare.",
  sql: "Write the query again from scratch in Try it.",
  lld: "Outline the design from memory in 15 minutes, then compare.",
  hld: "Outline the design from memory in 15 minutes, then compare.",
};

const DAY: Intl.DateTimeFormatOptions = { weekday: "short", day: "numeric", month: "short" };

/** A calendar date from the server ("2026-09-23"), which is already a day in India time. */
export const formatDay = (isoDate: string) => new Date(`${isoDate}T00:00:00`).toLocaleDateString(undefined, DAY);

/** A moment from the server, which arrives in UTC: shown as the day it was where the learner is. */
const formatMoment = (isoInstant: string) => new Date(isoInstant).toLocaleDateString(undefined, DAY);

/**
 * The only place progress is recorded: the learner says how it went. Reading, opening solutions
 * and running queries count for nothing, so looking around never marks a unit done.
 */
export function ProgressPanel({ unitId, type }: { unitId: string; type: string }) {
  const [progress, setProgress] = useState<Progress | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchProgress(unitId).then(setProgress).catch((e: Error) => setError(e.message));
  }, [unitId]);

  async function act(call: () => Promise<Progress>) {
    setBusy(true);
    setError(null);
    try {
      setProgress(await call());
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (!progress) return error ? <p role="alert">{error}</p> : null;
  const done = progress.attempts > 0;
  const asking = !done || progress.reviewDue;

  return (
    <section className="progress" aria-label="Your progress">
      <h3>{!done ? "Finished with this unit?" : progress.reviewDue ? "Review due" : "Done"}</h3>
      {done && (
        <p className="hint">
          Done on {formatMoment(progress.firstDoneAt!)}
          {progress.attempts > 1 && <>, reviewed {progress.attempts - 1}×</>}. Last time: {progress.lastRating}.{" "}
          {progress.reviewDue ? HOW_TO_REVIEW[type] ?? "Recall the key points before reopening it." : <>Next review {formatDay(progress.dueOn!)}.</>}
        </p>
      )}
      {asking && (
        <>
          <p className="hint">{done ? "How did the review go?" : "Mark it done by saying how it went. This schedules its reviews."}</p>
          <div className="ratings" role="group" aria-label="How did it go?">
            {RATINGS.map((r) => (
              <button key={r.rating} title={r.hint} disabled={busy} onClick={() => act(() => recordAttempt(unitId, r.rating))}>
                {r.label}
                <small>{r.hint}</small>
              </button>
            ))}
          </div>
        </>
      )}
      {done && (
        <button className="link" disabled={busy} onClick={() => act(() => undoAttempt(unitId))}>
          {progress.attempts > 1 ? "Undo the last review" : "Undo: not done after all"}
        </button>
      )}
      {error && <p role="alert">{error}</p>}
    </section>
  );
}
