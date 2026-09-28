import { useEffect, useState } from "react";
import { Link } from "react-router";
import { fetchProjectReviews, fetchReviews, type ProjectReviewQueue, type ReviewQueue } from "../api";
import { RUNG_LABEL } from "../projects/rungs";
import { formatDay } from "../unit/ProgressPanel";
import { TYPE_LABEL } from "../unit/sections";

/**
 * Reviews due today or overdue: units, then questions about the learner's own projects under their
 * own heading. Each shows nothing until something of its kind has been rated.
 */
export function ReviewsDue() {
  return (
    <>
      <UnitReviews />
      <ProjectReviews />
    </>
  );
}

function UnitReviews() {
  const [queue, setQueue] = useState<ReviewQueue | null>(null);

  useEffect(() => {
    // The curriculum below still works without this, so a failure just hides the section.
    fetchReviews().then(setQueue).catch(() => setQueue(null));
  }, []);

  if (!queue || (queue.due.length === 0 && !queue.nextDueOn)) return null;
  return (
    <section className="reviews-due">
      <h3>Reviews due{queue.due.length > 0 && <span className="count"> {queue.due.length}</span>}</h3>
      {queue.due.length === 0 ? (
        <p className="hint">Nothing due. The next review is on {formatDay(queue.nextDueOn!)}.</p>
      ) : (
        <ul className="units">
          {queue.due.map((d) => (
            <li key={d.unitId}>
              <Link to={`/units/${d.unitId}`}>{d.title}</Link>
              <span className="count">
                {TYPE_LABEL[d.type] ?? d.type} · due {formatDay(d.dueOn)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function ProjectReviews() {
  const [queue, setQueue] = useState<ProjectReviewQueue | null>(null);

  useEffect(() => {
    fetchProjectReviews().then(setQueue).catch(() => setQueue(null));
  }, []);

  if (!queue || (queue.due.length === 0 && !queue.nextDueOn)) return null;
  return (
    <section className="reviews-due">
      <h3>Project questions due{queue.due.length > 0 && <span className="count"> {queue.due.length}</span>}</h3>
      {queue.due.length === 0 ? (
        <p className="hint">Nothing due. The next one is on {formatDay(queue.nextDueOn!)}.</p>
      ) : (
        <ul className="units">
          {queue.due.map((d) => (
            <li key={d.questionId}>
              <Link to={`/projects/${d.projectId}#q-${d.questionId}`}>{d.prompt}</Link>
              <span className="count">
                {d.projectName} · {RUNG_LABEL[d.rung] ?? d.rung} · due {formatDay(d.dueOn)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
