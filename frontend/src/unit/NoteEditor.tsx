import { useEffect, useState } from "react";
import { fetchNote, saveNote } from "../api";

const LIMIT = 20_000;

/**
 * A private note on a unit. For a project deep dive this is where the learner's own answers go —
 * their systems, which never go into the shared curriculum.
 *
 * The note stays locked until it has loaded: saving over a note that failed to load would
 * replace the saved answer with whatever was typed into the empty box.
 */
export function NoteEditor({ unitId }: { unitId: string }) {
  const [body, setBody] = useState("");
  const [savedBody, setSavedBody] = useState("");
  const [savedAt, setSavedAt] = useState<string | null>(null);
  const [status, setStatus] = useState<"loading" | "load-failed" | "idle" | "saving" | "error">("loading");
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    // A slow answer for the unit left behind must not land in this unit's box.
    let current = true;
    setStatus("loading");
    fetchNote(unitId)
      .then((note) => {
        if (!current) return;
        setBody(note.body);
        setSavedBody(note.body);
        setSavedAt(note.updatedAt);
        setStatus("idle");
      })
      .catch(() => {
        if (current) setStatus("load-failed");
      });
    return () => {
      current = false;
    };
  }, [unitId, attempt]);

  const dirty = body !== savedBody;

  // Closing the tab or reloading with unsaved words asks first.
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  async function save() {
    setStatus("saving");
    try {
      const note = await saveNote(unitId, body);
      setSavedBody(note.body);
      setSavedAt(note.updatedAt);
      setStatus("idle");
    } catch {
      setStatus("error");
    }
  }

  const locked = status === "loading" || status === "load-failed";
  return (
    <section className="notes">
      <h2>Your notes</h2>
      <p className="hint">Only you can see these.</p>
      <label htmlFor="note" className="visually-hidden">Your notes on this unit</label>
      <textarea
        id="note"
        value={body}
        maxLength={LIMIT}
        rows={6}
        disabled={locked}
        onChange={(e) => setBody(e.target.value)}
      />
      <div className="note-actions">
        {status === "load-failed" ? (
          <button type="button" onClick={() => setAttempt((n) => n + 1)}>Try loading it again</button>
        ) : (
          <button type="button" onClick={save} disabled={!dirty || locked || status === "saving"}>
            {status === "saving" ? "Saving…" : "Save note"}
          </button>
        )}
        <span role="status">
          {status === "load-failed"
            ? "Could not load your note."
            : status === "error"
              ? "Could not save — try again."
              : dirty
                ? "Unsaved changes"
                : savedAt
                  ? `Saved ${new Date(savedAt).toLocaleString()}`
                  : ""}
        </span>
      </div>
    </section>
  );
}
