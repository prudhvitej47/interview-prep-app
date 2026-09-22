import { useEffect, useState } from "react";
import { Link } from "react-router";
import { fetchDrafts, fetchEvidence, type Draft, type EvidenceSummary } from "../api";
import { formatMoment } from "../unit/ProgressPanel";

const SOURCE: Record<string, string> = {
  "candidate-report": "Candidate report",
  "official-guide": "Official guide",
  "prep-guide": "Prep guide",
  news: "News",
  "curated-bank": "Question bank",
  "first-hand": "First-hand debrief",
  "user-provided": "Provided by a learner",
};

export const sourceLabel = (kind: string) => SOURCE[kind] ?? kind;

export function EvidencePage() {
  const [reports, setReports] = useState<EvidenceSummary[] | null>(null);
  const [drafts, setDrafts] = useState<Draft[]>([]);
  const [company, setCompany] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchEvidence().then(setReports).catch((e: Error) => setError(e.message));
    fetchDrafts().then(setDrafts).catch(() => setDrafts([]));
  }, []);

  const companies = [...new Map((reports ?? []).map((r) => [r.companyId, r.company])).entries()]
    .sort((a, b) => a[1].localeCompare(b[1]));
  const shown = (reports ?? []).filter((r) => !company || r.companyId === company);

  return (
    <article className="evidence">
      <nav className="crumbs" aria-label="Breadcrumb"><Link to="/">Home</Link> › Interview evidence</nav>
      <h2>Interview evidence</h2>
      <p className="hint">
        The reports the curriculum is built on. Questions are paraphrased; each maps to the topics it tests.
      </p>

      <section className="card" aria-label="Add to the evidence">
        <h3>Add to the evidence</h3>
        <p className="hint">
          Your own interview: log a debrief, private to you until you send it. Something you read: add the article, and
          an ingest run structures it. Either way it reaches the curriculum as a proposal you review.
        </p>
        <p>
          <Link to="/evidence/drafts/new">Log an interview debrief</Link> ·{" "}
          <Link to="/evidence/articles/new">Add an article or experience</Link>
        </p>
        {drafts.length > 0 && (
          <ul className="units">
            {drafts.map((d) => (
              <li key={d.id}>
                <Link to={`/evidence/drafts/${d.id}`}>
                  Debrief: {d.body.company ?? "company not chosen"}
                  {d.body.interview_date ? `, ${d.body.interview_date}` : ""}
                </Link>
                <span className="count">
                  {d.sentAt ? `sent ${formatMoment(d.sentAt)}` : `draft, edited ${formatMoment(d.updatedAt)}`}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      {error && <p role="alert">{error}</p>}
      {reports && (
        <>
          <label className="filter">
            Company{" "}
            <select value={company} onChange={(e) => setCompany(e.target.value)}>
              <option value="">All ({reports.length})</option>
              {companies.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
            </select>
          </label>
          <ul className="reports">
            {shown.map((r) => (
              <li key={r.id}>
                <Link to={`/evidence/${r.id}`}>
                  {r.company}{r.level ? ` · ${r.level}` : ""}{r.location ? ` · ${r.location}` : ""}
                </Link>
                <span className="count">
                  {r.interviewDate ?? "undated"} · {sourceLabel(r.sourceKind)}{r.publisher ? ` (${r.publisher})` : ""} ·{" "}
                  {r.rounds} round{r.rounds === 1 ? "" : "s"}, {r.questions} question{r.questions === 1 ? "" : "s"}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </article>
  );
}
