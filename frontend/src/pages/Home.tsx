import { useEffect, useState } from "react";
import { Link, useLocation } from "react-router";
import { Dashboard } from "./Dashboard";
import { RewardsStrip } from "./RewardsStrip";
import { ReviewsDue } from "./ReviewsDue";
import { fetchDomains, fetchTopics, type Domain, type Me, type TopicSummary } from "../api";

export function Home({ me }: { me: Me }) {
  const [domains, setDomains] = useState<Domain[] | null>(null);
  const [topics, setTopics] = useState<TopicSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const location = useLocation();

  useEffect(() => {
    Promise.all([fetchDomains(), fetchTopics()])
      .then(([d, t]) => {
        setDomains(d);
        setTopics(t);
      })
      .catch((e: Error) => setError(e.message));
  }, []);

  // A breadcrumb's domain link lands here as /#domain-<id>; the list loads after the page, so the
  // browser's own jump to the anchor has nothing to jump to yet. Scroll once the domains are drawn.
  useEffect(() => {
    if (domains && location.hash.startsWith("#domain-")) {
      document.getElementById(location.hash.slice(1))?.scrollIntoView?.({ block: "start" });
    }
  }, [domains, location.hash]);

  return (
    <>
      <div className="welcome">
        <p>Welcome, {me.displayName}.</p>
        <span>
          <Link to="/week">This week's plan</Link> · <Link to="/evidence">Interview evidence</Link> ·{" "}
          <Link to="/ratings">Change my ratings</Link>
        </span>
      </div>
      <RewardsStrip />
      <ReviewsDue />
      <Dashboard />
      <h3 className="browse">The curriculum</h3>
      {error && <p role="alert">{error}</p>}
      {!domains && !error && <p>Loading the curriculum…</p>}
      {domains?.length === 0 && <p>No curriculum has been loaded yet.</p>}
      {domains?.map((domain) => {
        // Subtopics are on the topic page; here each topic already counts its subtopics' units.
        const top = topics.filter((t) => t.domainId === domain.id && t.parentId === null);
        const units = top.reduce((sum, t) => sum + t.unitCount, 0);
        return (
          <details key={domain.id} id={`domain-${domain.id}`} className="domain"
            open={units > 0 || location.hash === `#domain-${domain.id}`}>
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
