import { useEffect, useRef, useState } from "react";
import { fetchProgress, recordAttempt, undoAttempt, type Progress, type Rating } from "../api";
import { moveFocusTo } from "../navigation";

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
export const formatMoment = (isoInstant: string) => new Date(isoInstant).toLocaleDateString(undefined, DAY);

/** What the panel says, which differs between a unit and a question about a learner's own project. */
export type RatingWords = {
  label: string;
  notDone: string;
  askFirst: string;
  doneOn: string;
  undoFirst: string;
};

const UNIT_WORDS: RatingWords = {
  label: "Your progress",
  notDone: "Finished with this unit?",
  askFirst: "Mark it done by saying how it went. This schedules its reviews.",
  doneOn: "Done on",
  undoFirst: "Undo: not done after all",
};

/**
 * The only place progress is recorded: the learner says how it went. Reading, opening solutions
 * and running queries count for nothing, so looking around never marks a unit done.
 */
export function ProgressPanel({ unitId, type }: { unitId: string; type: string }) {
  const [progress, setProgress] = useState<Progress | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchProgress(unitId).then(setProgress).catch((e: Error) => setError(e.message));
  }, [unitId]);

  if (!progress) return error ? <p role="alert">{error}</p> : null;
  return (
    <RatingPanel
      progress={progress}
      onProgress={setProgress}
      rate={(rating) => recordAttempt(unitId, rating)}
      undo={() => undoAttempt(unitId)}
      words={UNIT_WORDS}
      howToReview={HOW_TO_REVIEW[type]}
    />
  );
}

/**
 * The four ratings and Undo, for anything rehearsed on the review ladder. The caller holds the
 * progress, so it can react to a rating (a project question unfolds its strong answer).
 */
export function RatingPanel({ progress, onProgress, rate, undo, words, howToReview, level = 3 }: {
  progress: Progress;
  onProgress: (progress: Progress) => void;
  rate: (rating: Rating) => Promise<Progress>;
  undo: () => Promise<Progress>;
  words: RatingWords;
  howToReview?: string;
  level?: 3 | 4;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // The button just pressed disappears with the answer (a rating, or Undo), so the keyboard's place
  // moves to the heading, which now says what happened: "Done", "Review due".
  const heading = useRef<HTMLHeadingElement>(null);
  const [answered, setAnswered] = useState(0);

  useEffect(() => {
    if (answered > 0) moveFocusTo(heading.current);
  }, [answered]);

  async function act(call: () => Promise<Progress>) {
    setBusy(true);
    setError(null);
    try {
      onProgress(await call());
      setAnswered((n) => n + 1);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const done = progress.attempts > 0;
  const asking = !done || progress.reviewDue;
  const Heading = level === 4 ? "h4" : "h3";

  return (
    <section className="progress" aria-label={words.label}>
      <Heading ref={heading}>{!done ? words.notDone : progress.reviewDue ? "Review due" : "Done"}</Heading>
      {done && (
        <p className="hint">
          {words.doneOn} {formatMoment(progress.firstDoneAt!)}
          {progress.attempts > 1 && <>, reviewed {progress.attempts - 1}×</>}. Last time: {progress.lastRating}.{" "}
          {progress.reviewDue ? howToReview ?? "Recall the key points before reopening it." : <>Next review {formatDay(progress.dueOn!)}.</>}
        </p>
      )}
      {asking && (
        <>
          <p className="hint">{done ? "How did the review go?" : words.askFirst}</p>
          <div className="ratings" role="group" aria-label="How did it go?">
            {RATINGS.map((r) => (
              <button key={r.rating} title={r.hint} disabled={busy} onClick={() => act(() => rate(r.rating))}>
                {r.label}
                <small>{r.hint}</small>
              </button>
            ))}
          </div>
        </>
      )}
      {done && (
        <button className="link" disabled={busy} onClick={() => act(undo)}>
          {progress.attempts > 1 ? "Undo the last review" : words.undoFirst}
        </button>
      )}
      {error && <p role="alert">{error}</p>}
    </section>
  );
}
