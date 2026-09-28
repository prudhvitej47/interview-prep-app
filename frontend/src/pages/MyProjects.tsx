import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import { exportProjects, fetchProjects, importProjects, type ProjectImport, type ProjectSummary } from "../api";

const MAX_BYTES = 1024 * 1024;

type Outcome = { kind: "imported"; result: ProjectImport } | { kind: "refused"; problems: string[] } | { kind: "failed"; message: string };

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/** "2 projects added, 1 updated": only what changed, in words. */
function changes(counts: [number, string][], one: string, many: string): string | null {
  const said = counts.filter(([n]) => n > 0);
  if (said.length === 0) return null;
  return said.map(([n, verb], i) => (i === 0 ? `${plural(n, one, many)} ${verb}` : `${n} ${verb}`)).join(", ");
}

export function importSummary(r: ProjectImport): string {
  const said = [
    changes([[r.projectsAdded, "added"], [r.projectsUpdated, "updated"], [r.projectsRetired, "retired"]], "project", "projects"),
    changes([[r.questionsAdded, "added"], [r.questionsUpdated, "updated"], [r.questionsRetired, "retired"]], "question", "questions"),
  ].filter(Boolean);
  return said.length === 0 ? "Imported. Nothing had changed since the last import." : `Imported: ${said.join("; ")}.`;
}

/**
 * Questions an interviewer would ask about the learner's own projects, and their answers. They come
 * from a file the learner keeps outside Git and imports here; importing it again after an edit is
 * safe, since answers and ratings are kept.
 */
export function MyProjects() {
  const [projects, setProjects] = useState<ProjectSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [busy, setBusy] = useState(false);
  const picker = useRef<HTMLInputElement>(null);

  const load = () =>
    fetchProjects()
      .then(setProjects)
      .catch((e: Error) => setError(e.message));

  useEffect(() => {
    load();
  }, []);

  async function chosen(file: File | undefined) {
    if (!file) return;
    setOutcome(null);
    // Checked here too, so a wrong file is refused before it is read into the page.
    if (file.size > MAX_BYTES) {
      setOutcome({ kind: "refused", problems: ["The file is larger than 1 MB."] });
      return;
    }
    setBusy(true);
    try {
      const result = await importProjects(await file.text());
      if ("problems" in result) {
        setOutcome({ kind: "refused", problems: result.problems });
      } else {
        setOutcome({ kind: "imported", result });
        await load();
      }
    } catch (e) {
      setOutcome({ kind: "failed", message: (e as Error).message });
    } finally {
      setBusy(false);
    }
  }

  async function download() {
    setOutcome(null);
    try {
      const url = URL.createObjectURL(await exportProjects());
      const link = document.createElement("a");
      link.href = url;
      link.download = "my-projects.json";
      link.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setOutcome({ kind: "failed", message: (e as Error).message });
    }
  }

  return (
    <article className="my-projects">
      <h2>My projects</h2>
      <p>
        Questions an interviewer asks about your own work, and your answers to them. Only you can see these.
      </p>
      <div className="note-actions">
        <button type="button" disabled={busy} onClick={() => picker.current?.click()}>
          {busy ? "Importing…" : "Import a question file"}
        </button>
        <button type="button" className="secondary" disabled={busy || !projects?.length} onClick={download}>
          Export
        </button>
        <input
          ref={picker}
          type="file"
          accept="application/json,.json"
          className="visually-hidden"
          tabIndex={-1}
          aria-label="Question file"
          onChange={(e) => {
            const file = e.target.files?.[0];
            // Cleared, so choosing the same file again after fixing it still counts as a change.
            e.target.value = "";
            void chosen(file);
          }}
        />
      </div>

      {outcome?.kind === "imported" && (
        <div className="import-result" role="status">
          <p>{importSummary(outcome.result)}</p>
          {outcome.result.questionsRetired + outcome.result.projectsRetired > 0 && (
            <p className="hint">Retired ones were missing from the file. They are hidden, and their answers are kept.</p>
          )}
          {outcome.result.unknownUnits.length > 0 && (
            <p className="hint">
              Not in the curriculum, so left out of the links: {outcome.result.unknownUnits.join(", ")}.
            </p>
          )}
        </div>
      )}
      {outcome?.kind === "refused" && (
        <div className="import-result" role="alert">
          <p>Nothing was imported. Fix these and try again:</p>
          <ul>
            {outcome.problems.map((p, i) => <li key={i}>{p}</li>)}
          </ul>
        </div>
      )}
      {outcome?.kind === "failed" && <p role="alert">{outcome.message}</p>}

      {error && <p role="alert">{error}</p>}
      {!projects && !error && <p>Loading…</p>}
      {projects?.length === 0 && (
        <p className="hint">
          No projects yet. Import your question file to start; <Link to="/how-it-works#h-projects">How this
          works</Link> describes it.
        </p>
      )}
      {projects && projects.length > 0 && (
        <ul className="units projects">
          {projects.map((p) => (
            <li key={p.id}>
              <Link to={`/projects/${p.id}`}>{p.name}</Link>
              <span className="count">
                {plural(p.questions, "question", "questions")} · {p.answered} answered
                {p.due > 0 && <> · {p.due} due</>}
              </span>
            </li>
          ))}
        </ul>
      )}
    </article>
  );
}
