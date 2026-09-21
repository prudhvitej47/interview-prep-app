import { useEffect, useState } from "react";
import { fetchNote, saveNote } from "../api";

const LIMIT = 20_000;

/**
 * A private note on a unit. For a project deep dive this is where the learner's own answers go —
 * their systems, which never go into the shared curriculum.
 */
export function NoteEditor({ unitId }: { unitId: string }) {
  const [body, setBody] = useState("");
  const [savedBody, setSavedBody] = useState("");
  const [savedAt, setSavedAt] = useState<string | null>(null);
  const [status, setStatus] = useState<"loading" | "idle" | "saving" | "error">("loading");

  useEffect(() => {
    fetchNote(unitId)
      .then((note) => {
        setBody(note.body);
        setSavedBody(note.body);
        setSavedAt(note.updatedAt);
        setStatus("idle");
      })
      .catch(() => setStatus("error"));
  }, [unitId]);

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

  const dirty = body !== savedBody;
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
        disabled={status === "loading"}
        onChange={(e) => setBody(e.target.value)}
      />
      <div className="note-actions">
        <button type="button" onClick={save} disabled={!dirty || status === "saving"}>
          {status === "saving" ? "Saving…" : "Save note"}
        </button>
        <span role="status">
          {status === "error"
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
