import { useEffect, useState } from "react";
import { Link } from "react-router";
import { fetchWeek, type PlanItem, type Week } from "../api";

/**
 * Previous and next inside this week's plan, at the foot of a unit that is in it.
 *
 * A study session is a run through the week's list, so the list should not have to be revisited
 * between two units. Units not in this week's plan (browsing the curriculum) show nothing.
 */
export function PlanNav({ unitId }: { unitId: string }) {
  const [week, setWeek] = useState<Week | null>(null);

  useEffect(() => {
    fetchWeek().then(setWeek).catch(() => setWeek(null));
  }, []);

  const items = ordered(week?.plan?.items ?? []);
  const here = items.findIndex((i) => i.unitId === unitId);
  if (here === -1) return null;
  const previous = items[here - 1];
  const next = items[here + 1];

  return (
    <nav className="plan-nav" aria-label="This week's plan">
      <span>
        {previous && <Link to={`/units/${previous.unitId}`} rel="prev">← {previous.title}</Link>}
      </span>
      <Link to="/week">This week's plan ({here + 1} of {items.length})</Link>
      <span>
        {next ? (
          <Link to={`/units/${next.unitId}`} rel="next">{next.title} →</Link>
        ) : (
          <span className="count">Last in the week</span>
        )}
      </span>
    </nav>
  );
}

/** Day order, and within a day the order the plan put them in. One entry per unit. */
function ordered(items: PlanItem[]): PlanItem[] {
  const seen = new Set<string>();
  return items
    .map((item, i) => ({ item, i }))
    .sort((a, b) => a.item.day - b.item.day || a.i - b.i)
    .map(({ item }) => item)
    .filter((item) => !seen.has(item.unitId) && seen.add(item.unitId));
}
