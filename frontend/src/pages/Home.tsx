import { useEffect, useState } from "react";
import { Link } from "react-router";
import { ReviewsDue } from "./ReviewsDue";
import { fetchDomains, fetchTopics, type Domain, type Me, type TopicSummary } from "../api";

export function Home({ me }: { me: Me }) {
  const [domains, setDomains] = useState<Domain[] | null>(null);
  const [topics, setTopics] = useState<TopicSummary[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([fetchDomains(), fetchTopics()])
      .then(([d, t]) => {
        setDomains(d);
        setTopics(t);
      })
      .catch((e: Error) => setError(e.message));
  }, []);

  return (
    <>
      <div className="welcome">
        <p>Welcome, {me.displayName}.</p>
        <Link to="/ratings">Change my ratings</Link>
      </div>
      <ReviewsDue />
      {error && <p role="alert">{error}</p>}
      {!domains && !error && <p>Loading the curriculum…</p>}
      {domains?.length === 0 && <p>No curriculum has been loaded yet.</p>}
      {domains?.map((domain) => {
        // Subtopics are on the topic page; here each topic already counts its subtopics' units.
        const top = topics.filter((t) => t.domainId === domain.id && t.parentId === null);
        const units = top.reduce((sum, t) => sum + t.unitCount, 0);
        return (
          <details key={domain.id} className="domain" open={units > 0}>
            <summary>
              {domain.name} <span className="count">{units === 1 ? "1 unit" : `${units} units`}</span>
            </summary>
            <ul>
              {top.map((t) => (
                <li key={t.id}>
                  {t.unitCount > 0 ? (
                    <Link to={`/topics/${t.id}`}>{t.name}</Link>
                  ) : (
                    <span className="empty">{t.name}</span>
                  )}
                  {t.unitCount > 0 && <span className="count"> {t.unitCount}</span>}
                </li>
              ))}
            </ul>
          </details>
        );
      })}
    </>
  );
}
