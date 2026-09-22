import { useEffect, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router";
import {
  deleteDraft, exportDraft, fetchDraft, fetchEvidenceOptions, fetchTopics, saveDraft,
  type DraftBody, type DraftRound, type Option, type TopicSummary,
} from "../api";

const OUTCOMES = ["offer", "no-offer", "downlevelled", "declined", "unknown", "not-applicable"];
const REPORT_KINDS: [string, string][] = [
  ["candidate-report", "Candidate report (Medium, Taro, LeetCode, Glassdoor…)"],
  ["prep-guide", "Prep guide"],
  ["official-guide", "The company's own guide"],
  ["curated-bank", "Question bank"],
  ["news", "News"],
  ["user-provided", "Something else"],
];

/**
 * A debrief after a real interview, or a report found elsewhere, written as the content repo's
 * evidence record so it can be exported as a file. Saved as a draft in the app, private to the
 * learner, until they choose to export it.
 */
export function DraftPage() {
  const { draftId } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  // One route for new and existing drafts ("new" is the id of one not saved yet), so the first save
  // can move the address to the draft's id without remounting the form and losing what it shows.
  const [id, setId] = useState<number | undefined>(draftId && draftId !== "new" ? Number(draftId) : undefined);
  const [kind, setKind] = useState<"debrief" | "report">(params.get("kind") === "report" ? "report" : "debrief");
  const [body, setBody] = useState<DraftBody>({ rounds: [{ type: "" }] });
  const [options, setOptions] = useState<{ companies: Option[]; rounds: Option[] }>({ companies: [], rounds: [] });
  const [topics, setTopics] = useState<TopicSummary[]>([]);
  const [status, setStatus] = useState<string | null>(null);
  const [problems, setProblems] = useState<string[]>([]);
  const [exported, setExported] = useState<{ path: string; yaml: string } | null>(null);

  useEffect(() => {
    fetchEvidenceOptions().then(setOptions).catch(() => undefined);
    fetchTopics().then(setTopics).catch(() => undefined);
  }, []);

  useEffect(() => {
    // Loads a draft opened by its address; one this page just created is already on screen.
    if (draftId && draftId !== "new" && Number(draftId) !== id) {
      fetchDraft(Number(draftId)).then((d) => {
        setId(d.id);
        setKind(d.kind);
        setBody({ ...d.body, rounds: d.body.rounds?.length ? d.body.rounds : [{ type: "" }] });
      }).catch((e: Error) => setStatus(e.message));
    }
    // Deliberately only on the address: saving changes the id, and must not reload the form.
  }, [draftId]);

  const set = (patch: Partial<DraftBody>) => {
    setBody({ ...body, ...patch });
    setExported(null);
  };
  const rounds = body.rounds ?? [];
  const setRound = (i: number, r: DraftRound | null) =>
    set({ rounds: r === null ? rounds.filter((_, j) => j !== i) : rounds.map((x, j) => (j === i ? r : x)) });

  async function save(): Promise<number> {
    const saved = await saveDraft({ id, kind, body });
    if (!id) {
      setId(saved.id);
      navigate(`/evidence/drafts/${saved.id}`, { replace: true });
    }
    setStatus("Saved.");
    return saved.id;
  }

  async function runExport() {
    setProblems([]);
    try {
      const result = await exportDraft(await save());
      if ("problems" in result) setProblems(result.problems);
      else setExported(result);
    } catch (e) {
      setStatus((e as Error).message);
    }
  }

  function download() {
    const url = URL.createObjectURL(new Blob([exported!.yaml], { type: "application/yaml" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = exported!.path.split("/").pop()!;
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <article className="evidence draft">
      <nav className="crumbs" aria-label="Breadcrumb">
        <Link to="/">Home</Link> › <Link to="/evidence">Interview evidence</Link> ›{" "}
        {kind === "debrief" ? "Interview debrief" : "A report you found"}
      </nav>
      <h2>{kind === "debrief" ? "Log an interview debrief" : "Add a report you found"}</h2>
      <p className="hint">
        Write questions in your own words, and leave out names and anything you agreed to keep confidential. This stays
        private to you until you export it.
      </p>

      <fieldset>
        <legend>The interview</legend>
        <label>Company{" "}
          <select value={body.company ?? ""} onChange={(e) => set({ company: e.target.value })}>
            <option value="">Choose…</option>
            {options.companies.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
        <label>Role <input value={body.role ?? ""} onChange={(e) => set({ role: e.target.value })} placeholder="Senior Software Engineer" /></label>
        <label>Level <input value={body.level ?? ""} onChange={(e) => set({ level: e.target.value })} placeholder="senior" /></label>
        <label>Location <input value={body.location ?? ""} onChange={(e) => set({ location: e.target.value })} placeholder="Bengaluru" /></label>
        <label>When <input value={body.interview_date ?? ""} onChange={(e) => set({ interview_date: e.target.value })} placeholder="2026-10" /></label>
        <label>Outcome{" "}
          <select value={body.outcome ?? ""} onChange={(e) => set({ outcome: e.target.value || undefined })}>
            <option value="">Not known yet</option>
            {OUTCOMES.map((o) => <option key={o} value={o}>{o}</option>)}
          </select>
        </label>
      </fieldset>

      {kind === "report" && (
        <fieldset>
          <legend>Where you found it</legend>
          <label>Kind{" "}
            <select value={body.source?.kind ?? ""} onChange={(e) => set({ source: { ...body.source, kind: e.target.value } })}>
              <option value="">Choose…</option>
              {REPORT_KINDS.map(([k, label]) => <option key={k} value={k}>{label}</option>)}
            </select>
          </label>
          <label>Title <input value={body.source?.title ?? ""} onChange={(e) => set({ source: { ...body.source, title: e.target.value } })} /></label>
          <label>Link <input value={body.source?.url ?? ""} onChange={(e) => set({ source: { ...body.source, url: e.target.value } })} placeholder="https://" /></label>
          <label>Site <input value={body.source?.publisher ?? ""} onChange={(e) => set({ source: { ...body.source, publisher: e.target.value } })} placeholder="Medium" /></label>
        </fieldset>
      )}

      <h3>Rounds</h3>
      {rounds.map((r, i) => (
        <fieldset key={i} className="round">
          <legend>Round {i + 1}</legend>
          <label>Type{" "}
            <select value={r.type} onChange={(e) => setRound(i, { ...r, type: e.target.value })} aria-label={`Round ${i + 1} type`}>
              <option value="">Choose…</option>
              {options.rounds.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
            </select>
          </label>
          <label>Summary <input value={r.summary ?? ""} onChange={(e) => setRound(i, { ...r, summary: e.target.value })} placeholder="60 minutes, two problems" /></label>
          {(r.questions ?? []).map((q, j) => (
            <Question key={j} n={j + 1} question={q} topics={topics}
              onChange={(nq) => setRound(i, { ...r, questions: (r.questions ?? []).map((x, k) => (k === j ? nq : x)) })}
              onRemove={() => setRound(i, { ...r, questions: (r.questions ?? []).filter((_, k) => k !== j) })} />
          ))}
          <p>
            <button type="button" className="link" onClick={() => setRound(i, { ...r, questions: [...(r.questions ?? []), { text: "", topics: [] }] })}>
              Add a question
            </button>{" · "}
            <button type="button" className="link" onClick={() => setRound(i, null)}>Remove this round</button>
          </p>
        </fieldset>
      ))}
      <p><button type="button" className="link" onClick={() => set({ rounds: [...rounds, { type: "" }] })}>Add a round</button></p>

      <label className="notes">Private notes (never exported)
        <textarea rows={4} value={body.private_notes ?? ""} onChange={(e) => set({ private_notes: e.target.value })} />
      </label>

      <div className="note-actions">
        <button onClick={() => save().catch((e: Error) => setStatus(e.message))}>Save draft</button>
        <button className="secondary" onClick={runExport}>Export as an evidence file</button>
        {id && (
          <button className="secondary" onClick={() => deleteDraft(id).then(() => navigate("/evidence"))}>Delete</button>
        )}
        <span role="status">{status}</span>
      </div>

      {problems.length > 0 && (
        <div role="alert" className="verdict wrong">
          <p>Before it can be exported:</p>
          <ul>{problems.map((p) => <li key={p}>{p}</li>)}</ul>
        </div>
      )}
      {exported && (
        <section className="card" aria-label="Evidence file">
          <h3>{exported.path}</h3>
          <p className="hint">
            Save it into the content repository's <code>inbox/</code>, or add it at this path on a <code>proposals/</code>{" "}
            branch. It is checked and reviewed like any other change before it reaches a plan.
          </p>
          <pre>{exported.yaml}</pre>
          <div className="note-actions">
            <button onClick={download}>Download</button>
            <button className="secondary" onClick={() => navigator.clipboard.writeText(exported.yaml)}>Copy</button>
          </div>
        </section>
      )}
    </article>
  );
}

function Question({ n, question, topics, onChange, onRemove }: {
  n: number;
  question: { text: string; topics: string[] };
  topics: TopicSummary[];
  onChange: (q: { text: string; topics: string[] }) => void;
  onRemove: () => void;
}) {
  const [pick, setPick] = useState("");
  const byId = new Map(topics.map((t) => [t.id, t.name]));
  const add = () => {
    if (byId.has(pick) && !question.topics.includes(pick)) onChange({ ...question, topics: [...question.topics, pick] });
    setPick("");
  };
  return (
    <div className="question">
      <label>Question {n}
        <input value={question.text} onChange={(e) => onChange({ ...question, text: e.target.value })}
          placeholder="Design idempotent retries for a payments API" />
      </label>
      <div className="chips">
        {question.topics.map((t) => (
          <span key={t} className="chip">
            {byId.get(t) ?? t}
            <button type="button" aria-label={`Remove ${byId.get(t) ?? t}`}
              onClick={() => onChange({ ...question, topics: question.topics.filter((x) => x !== t) })}>×</button>
          </span>
        ))}
        <input list="topic-ids" value={pick} onChange={(e) => setPick(e.target.value)} aria-label={`Question ${n} topic`}
          onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); add(); } }} placeholder="Add a topic…" />
        <button type="button" className="link" onClick={add}>Add</button>
        <button type="button" className="link" onClick={onRemove}>Remove question</button>
      </div>
      <datalist id="topic-ids">
        {topics.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
      </datalist>
    </div>
  );
}
