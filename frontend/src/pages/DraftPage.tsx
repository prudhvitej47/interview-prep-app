import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import {
  deleteDraft, exportDraft, fetchDraft, fetchEvidenceOptions, fetchTopics, saveDraft, sendDraft,
  type Draft, type DraftBody, type DraftRound, type Option, type TopicSummary,
} from "../api";
import { formatMoment } from "../unit/ProgressPanel";

const OUTCOMES = ["offer", "no-offer", "downlevelled", "declined", "unknown", "not-applicable"];
/**
 * A debrief after a real interview, written as the content repo's evidence record. Saved as a draft
 * in the app, private to the learner, until they send it to the curriculum as a proposal. Interviews
 * someone else wrote up go in through "Add an article" instead.
 */
export function DraftPage() {
  const { draftId } = useParams();
  const navigate = useNavigate();
  // One route for new and existing drafts ("new" is the id of one not saved yet), so the first save
  // can move the address to the draft's id without remounting the form and losing what it shows.
  const [id, setId] = useState<number | undefined>(draftId && draftId !== "new" ? Number(draftId) : undefined);
  const [body, setBody] = useState<DraftBody>({ rounds: [{ type: "" }] });
  const [options, setOptions] = useState<{ companies: Option[]; rounds: Option[] }>({ companies: [], rounds: [] });
  const [topics, setTopics] = useState<TopicSummary[]>([]);
  const [status, setStatus] = useState<string | null>(null);
  const [problems, setProblems] = useState<string[]>([]);
  const [exported, setExported] = useState<{ path: string; yaml: string } | null>(null);
  const [sent, setSent] = useState<Pick<Draft, "sentAt" | "sentBranch" | "sentUrl"> | null>(null);
  const [notSetUp, setNotSetUp] = useState(false);

  useEffect(() => {
    fetchEvidenceOptions().then(setOptions).catch(() => undefined);
    fetchTopics().then(setTopics).catch(() => undefined);
  }, []);

  useEffect(() => {
    // Loads a draft opened by its address; one this page just created is already on screen.
    if (draftId && draftId !== "new" && Number(draftId) !== id) {
      fetchDraft(Number(draftId)).then((d) => {
        setId(d.id);
        if (d.sentAt) setSent(d);
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
    const saved = await saveDraft({ id, kind: "debrief", body });
    if (!id) {
      setId(saved.id);
      navigate(`/evidence/drafts/${saved.id}`, { replace: true });
    }
    setStatus("Saved.");
    return saved.id;
  }

  async function send() {
    setProblems([]);
    setStatus(null);
    try {
      const savedId = await save();
      const result = await sendDraft(savedId);
      if ("problems" in result) setProblems(result.problems);
      else if ("notSetUp" in result) {
        setNotSetUp(true);
        const file = await exportDraft(savedId);
        if ("yaml" in file) setExported(file);
      } else setSent({ sentAt: new Date().toISOString(), sentBranch: result.branch, sentUrl: result.url });
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
        Interview debrief
      </nav>
      <h2>Log an interview debrief</h2>
      <p className="hint">
        Write questions in your own words, and leave out names and anything you agreed to keep confidential. This stays
        private to you until you send it.
      </p>
      {sent && (
        <p className="banner" role="note">
          Sent to the curriculum {sent.sentAt && formatMoment(sent.sentAt)} as <code>{sent.sentBranch}</code>. A pull
          request opens by itself within a minute: <a href={sent.sentUrl!} target="_blank" rel="noreferrer noopener">see
          it on GitHub</a>. Changes from here on belong there.
        </p>
      )}
      <fieldset disabled={!!sent} className="plain">

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

      </fieldset>

      <div className="note-actions">
        {!sent && <button onClick={send}>Send to the curriculum</button>}
        {!sent && (
          <button className="secondary" onClick={() => save().catch((e: Error) => setStatus(e.message))}>Save draft</button>
        )}
        {id && (
          <button className="secondary" onClick={() => deleteDraft(id).then(() => navigate("/evidence"))}>Delete</button>
        )}
        <span role="status">{status}</span>
      </div>
      {!sent && (
        <p className="hint">
          Sending puts it on a <code>proposals/</code> branch of the curriculum, where a pull request opens by itself for
          review. Your private notes and your name are never sent.
        </p>
      )}

      {problems.length > 0 && (
        <div role="alert" className="verdict wrong">
          <p>Before it can be sent:</p>
          <ul>{problems.map((p) => <li key={p}>{p}</li>)}</ul>
        </div>
      )}
      {exported && (
        <section className="card" aria-label="Evidence file">
          <h3>{exported.path}</h3>
          <p className="hint">
            {notSetUp && "Sending is not set up on this server yet (see the deploy README), so here is the file instead. "}
            Add it at this path on a <code>proposals/</code> branch of the content repository, or put it in{" "}
            <code>inbox/</code>. It is reviewed like any other change before it reaches a plan.
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
