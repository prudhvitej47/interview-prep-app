import { useEffect, useState } from "react";
import { saveAnswer } from "../api";
import { useUnsavedGuard } from "../unit/useUnsavedGuard";

const LIMIT = 20_000;

/** How long typing has to pause before the answer saves itself. */
export const AUTOSAVE_MS = 1500;

/**
 * The learner's own answer to a project question. It saves itself a moment after typing stops, and
 * at once when the box loses focus; leaving with words not yet saved asks first, as a note does.
 *
 * <p>A failed save does not retry by itself until the text changes, so a dropped connection is not
 * hammered every second and a half; "Try again" is there for the impatient.
 */
export function AnswerBox({ questionId, initial, answeredAt, onSaved }: {
  questionId: number;
  initial: string;
  answeredAt: string | null;
  onSaved: (answer: string, answeredAt: string | null) => void;
}) {
  const [body, setBody] = useState(initial);
  const [savedBody, setSavedBody] = useState(initial);
  const [savedAt, setSavedAt] = useState(answeredAt);
  const [status, setStatus] = useState<"idle" | "saving" | "error">("idle");
  const [failed, setFailed] = useState<string | null>(null);

  const dirty = body !== savedBody;
  useUnsavedGuard(dirty, "Your answer has not been saved yet. Leave anyway?");

  async function save() {
    const text = body;
    setStatus("saving");
    try {
      const saved = await saveAnswer(questionId, text);
      setSavedBody(text);
      setSavedAt(saved.answeredAt);
      setFailed(null);
      setStatus("idle");
      onSaved(saved.answer, saved.answeredAt);
    } catch {
      setFailed(text);
      setStatus("error");
    }
  }

  useEffect(() => {
    if (!dirty || status === "saving" || body === failed) return;
    const timer = setTimeout(() => void save(), AUTOSAVE_MS);
    return () => clearTimeout(timer);
    // Re-arming the timer on every keystroke is the point; save reads the body as it is by then.
  }, [body, dirty, status, failed]);

  const id = `answer-${questionId}`;
  return (
    <div className="notes answer">
      <label htmlFor={id}>Your answer</label>
      <textarea
        id={id}
        value={body}
        maxLength={LIMIT}
        rows={5}
        placeholder="Answer as you would out loud: the situation, what you did, and why."
        onChange={(e) => setBody(e.target.value)}
        onBlur={() => {
          if (dirty && status !== "saving") void save();
        }}
      />
      <div className="note-actions">
        {status === "error" && (
          <button type="button" className="secondary" onClick={() => void save()}>Try again</button>
        )}
        <span role="status">
          {status === "saving"
            ? "Saving…"
            : status === "error"
              ? "Could not save your answer."
              : dirty
                ? "Not saved yet"
                : savedAt
                  ? `Saved ${new Date(savedAt).toLocaleString()}. Only you can see it.`
                  : "Saves as you type. Only you can see it."}
        </span>
      </div>
    </div>
  );
}
