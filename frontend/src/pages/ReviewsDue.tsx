import { useEffect, useState } from "react";
import { Link } from "react-router";
import { fetchReviews, type ReviewQueue } from "../api";
import { formatDay } from "../unit/ProgressPanel";
import { TYPE_LABEL } from "../unit/sections";

/** Reviews due today or overdue. Shows nothing until something has been marked done. */
export function ReviewsDue() {
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
