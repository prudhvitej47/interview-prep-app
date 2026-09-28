import { NotForMe } from "../unit/NotForMe";
import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { fetchProgressView, fetchTopic, NotFoundError, type TopicDetail, type UnitProgress } from "../api";
import { TYPE_LABEL } from "../unit/sections";
import { StageChip } from "./stages";

export function TopicPage() {
  const { topicId = "" } = useParams();
  const [topic, setTopic] = useState<TopicDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [progress, setProgress] = useState<Map<string, UnitProgress>>(new Map());

  // Each unit's status. If it cannot load, the list simply shows no chips; the units matter more.
  const loadProgress = useCallback(() => {
    fetchProgressView()
      .then((p) => setProgress(new Map(p.units.map((u) => [u.unitId, u]))))
      .catch(() => undefined);
  }, []);
  useEffect(loadProgress, [loadProgress]);

  useEffect(() => {
    setTopic(null);
    fetchTopic(topicId)
      .then(setTopic)
      .catch((e: Error) => setError(e instanceof NotFoundError ? "There is no such topic." : e.message));
  }, [topicId]);

  if (error) return <p role="alert">{error}</p>;
  if (!topic) return <p>Loading…</p>;

  return (
    <>
      <nav className="crumbs" aria-label="Breadcrumb">
        <Link to="/">Home</Link> › <Link to={`/#domain-${topic.domainId}`}>{topic.domainName}</Link>
        {topic.parentId && (
          <>
            {" › "}
            <Link to={`/topics/${topic.parentId}`}>{topic.parentName}</Link>
          </>
        )}
      </nav>
      <h2>{topic.name}</h2>
      <NotForMe scope="topic" id={topic.id} onChange={loadProgress} />

      {topic.units.length > 0 ? (
        <ul className="units">
          {topic.units.map((u) => {
            const p = progress.get(u.id);
            return (
              <li key={u.id}>
                <span className="unit-line">
                  <Link to={`/units/${u.id}`}>{u.title}</Link>
                  {p && <StageChip stage={p.stage} reviews={p.reviews} notForMe={p.notForMe} />}
                </span>
                <span className="meta">
                  {TYPE_LABEL[u.type] ?? u.type} · difficulty {u.difficulty}/5 · {u.estMinutes} min
                </span>
              </li>
            );
          })}
        </ul>
      ) : (
        <p className="empty">Nothing has been written for this topic itself yet.</p>
      )}

      {topic.subtopics.length > 0 && (
        <>
          <h3>Subtopics</h3>
          <ul>
            {topic.subtopics.map((s) => (
              <li key={s.id}>
                {s.unitCount > 0 ? <Link to={`/topics/${s.id}`}>{s.name}</Link> : <span className="empty">{s.name}</span>}
                {s.unitCount > 0 && <span className="count"> {s.unitCount}</span>}
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );
}
