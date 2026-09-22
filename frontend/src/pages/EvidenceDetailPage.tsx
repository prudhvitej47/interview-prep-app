import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { fetchEvidenceDetail, NotFoundError, type EvidenceDetail } from "../api";
import { sourceLabel } from "./EvidencePage";

export function EvidenceDetailPage() {
  const { evidenceId = "" } = useParams();
  const [detail, setDetail] = useState<EvidenceDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchEvidenceDetail(evidenceId).then(setDetail)
      .catch((e: Error) => setError(e instanceof NotFoundError ? "There is no such report." : e.message));
  }, [evidenceId]);

  if (error) return <p role="alert">{error}</p>;
  if (!detail) return <p>Loading…</p>;
  const s = detail.summary;
  return (
    <article className="evidence">
      <nav className="crumbs" aria-label="Breadcrumb">
        <Link to="/">Home</Link> › <Link to="/evidence">Interview evidence</Link> › {s.company}
      </nav>
      <h2>{s.company}{s.role ? `: ${s.role}` : ""}</h2>
      <p className="meta">
        {[s.level, s.location, s.interviewDate ?? "undated", s.outcome && `outcome: ${s.outcome}`].filter(Boolean).join(" · ")}
      </p>
      <p className="meta">
        {sourceLabel(s.sourceKind)} ({s.tier}):{" "}
        {detail.sourceUrl ? <a href={detail.sourceUrl} target="_blank" rel="noreferrer noopener">{s.sourceTitle}</a> : s.sourceTitle}
        {s.publisher && <>, {s.publisher}</>} · read {detail.accessed}
      </p>
      <ol className="rounds">
        {detail.rounds.map((r, i) => (
          <li key={i}>
            <strong>{r.name}</strong>{r.summary && <span className="hint"> · {r.summary}</span>}
            {r.questions.length > 0 && (
              <ul>
                {r.questions.map((q, j) => (
                  <li key={j}>
                    {q.text}{" "}
                    <span className="count">
                      {q.topics.map((t, k) => (
                        <span key={t.id}>{k > 0 && ", "}<Link to={`/topics/${t.id}`}>{t.name}</Link></span>
                      ))}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </li>
        ))}
      </ol>
      {detail.notes && <p className="hint">{detail.notes}</p>}
    </article>
  );
}
