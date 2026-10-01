import { useState } from "react";
import type { QuestionDraft, Rung, Saved } from "../api";
import { RUNG_LABEL } from "./rungs";

const RUNGS = Object.keys(RUNG_LABEL) as Rung[];

export const EMPTY_QUESTION: QuestionDraft = {
  rung: "walkthrough",
  prompt: "",
  probes: "",
  strongAnswer: "",
  units: [],
  minutes: 15,
};

/** Unit ids typed as a list: commas, spaces or new lines between them. */
export const parseUnits = (text: string) => text.split(/[\s,]+/).filter((id) => id !== "");

/**
 * Adding or changing a question. The answer is not here: it has its own box on the page, and saving
 * this form never touches it or the ratings.
 */
export function QuestionForm({ initial, submitLabel, save, onSaved, onCancel }: {
  initial: QuestionDraft;
  submitLabel: string;
  save: (draft: QuestionDraft) => Promise<Saved | { problems: string[] }>;
  onSaved: (saved: Saved) => void;
  onCancel: () => void;
}) {
  const [draft, setDraft] = useState(initial);
  const [units, setUnits] = useState(initial.units.join(", "));
  const [problems, setProblems] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const set = (update: Partial<QuestionDraft>) => setDraft((d) => ({ ...d, ...update }));

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setProblems([]);
    try {
      const result = await save({ ...draft, units: parseUnits(units) });
      if ("problems" in result) setProblems(result.problems);
      else onSaved(result);
    } catch (e) {
      setProblems([(e as Error).message]);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="draft project-form" onSubmit={submit}>
      <fieldset className="plain">
        <label>
          Angle
          <select value={draft.rung} onChange={(e) => set({ rung: e.target.value as Rung })}>
            {RUNGS.map((r) => <option key={r} value={r}>{RUNG_LABEL[r]}</option>)}
          </select>
        </label>
        <label>
          The question
          <textarea rows={2} required value={draft.prompt} onChange={(e) => set({ prompt: e.target.value })} />
        </label>
        <label>
          Follow-ups an interviewer pushes with (optional, Markdown)
          <textarea rows={3} value={draft.probes} onChange={(e) => set({ probes: e.target.value })} />
        </label>
        <label>
          What a strong answer covers (optional, Markdown)
          <textarea rows={4} value={draft.strongAnswer} onChange={(e) => set({ strongAnswer: e.target.value })} />
        </label>
        <label>
          Units that teach the idea underneath (optional ids, separated by commas)
          <input value={units} onChange={(e) => setUnits(e.target.value)} placeholder="ds.transactions.idempotency-keys" />
        </label>
        <label>
          Minutes to rehearse
          <input type="number" min={1} max={120} value={draft.minutes}
            onChange={(e) => set({ minutes: Number(e.target.value) })} />
        </label>
      </fieldset>
      {problems.length > 0 && (
        <div role="alert">
          <p>Not saved. Fix these and try again:</p>
          <ul>{problems.map((p, i) => <li key={i}>{p}</li>)}</ul>
        </div>
      )}
      <div className="note-actions">
        <button type="submit" disabled={busy}>{busy ? "Saving…" : submitLabel}</button>
        <button type="button" className="secondary" disabled={busy} onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}

/** A project's name and summary, for a new project or a change to one. */
export function ProjectForm({ initial, submitLabel, save, onSaved, onCancel }: {
  initial: { name: string; summary: string };
  submitLabel: string;
  save: (project: { name: string; summary: string }) => Promise<Saved | { problems: string[] }>;
  onSaved: (saved: Saved) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(initial.name);
  const [summary, setSummary] = useState(initial.summary);
  const [problems, setProblems] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setProblems([]);
    try {
      const result = await save({ name, summary });
      if ("problems" in result) setProblems(result.problems);
      else onSaved(result);
    } catch (e) {
      setProblems([(e as Error).message]);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="draft project-form" onSubmit={submit}>
      <fieldset className="plain">
        <label>
          Project name
          <input required value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label>
          What you did (optional, Markdown)
          <textarea rows={4} value={summary} onChange={(e) => setSummary(e.target.value)} />
        </label>
      </fieldset>
      {problems.length > 0 && (
        <div role="alert">
          <p>Not saved. Fix these and try again:</p>
          <ul>{problems.map((p, i) => <li key={i}>{p}</li>)}</ul>
        </div>
      )}
      <div className="note-actions">
        <button type="submit" disabled={busy}>{busy ? "Saving…" : submitLabel}</button>
        <button type="button" className="secondary" disabled={busy} onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}
