import { useEffect, useRef, useState, type ReactNode } from "react";
import { Link } from "react-router";
import {
  acceptSuggestedPlacements, fetchDashboard, fetchWeek, placeUnit, type Dashboard as Data, type Placement, type Week,
} from "../api";
import { moveFocusTo } from "../navigation";
import { Markdown } from "../unit/Markdown";
import { formatMoment } from "../unit/ProgressPanel";
import { TYPE_LABEL } from "../unit/sections";
import { itemHref, itemKey, itemLabel } from "./planItem";

const PLACEMENT: Record<Placement, string> = { now: "Now", "next-week": "Next week", "end-of-track": "Later" };

/** A release can bring hundreds of units; the rest wait behind "Show all". */
export const FIRST_ROWS = 10;

/**
 * The top of the home page. This week comes first, because it is what the learner came to do;
 * `children` (reviews due and the rewards strip) follow it; then a line of coverage, the weakest
 * areas and what the last release changed.
 */
export function Dashboard({ children }: { children?: ReactNode }) {
  const [data, setData] = useState<Data | null>(null);
  const [week, setWeek] = useState<Week | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showAll, setShowAll] = useState(false);
  const [busy, setBusy] = useState(false);
  const changedHeading = useRef<HTMLHeadingElement>(null);
  const placements = useRef<HTMLUListElement>(null);

  useEffect(() => {
    // Each part hides itself if it cannot load; the curriculum below still works.
    fetchDashboard().then(setData).catch(() => setData(null));
    fetchWeek().then(setWeek).catch(() => setWeek(null));
  }, []);

  async function place(unitId: string, choice: Placement) {
    setError(null);
    try {
      setData(await placeUnit(unitId, choice));
      setWeek(await fetchWeek());
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function acceptAll(unitIds: string[]) {
    setError(null);
    setBusy(true);
    try {
      setData(await acceptSuggestedPlacements(unitIds));
      setWeek(await fetchWeek());
      // The button has gone with the last unchosen row; the card's heading keeps the keyboard's place.
      moveFocusTo(changedHeading.current);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  function revealAll() {
    setShowAll(true);
    // The button has gone; the first row it uncovered is where the reader was heading.
    requestAnimationFrame(() =>
      moveFocusTo(placements.current?.children[FIRST_ROWS]?.querySelector<HTMLElement>("a") ?? null));
  }

  const coverage = data?.coverage ?? [];
  const done = coverage.reduce((sum, c) => sum + c.done, 0);
  const total = coverage.reduce((sum, c) => sum + c.total, 0);
  const reviewDue = coverage.reduce((sum, c) => sum + c.stages.reviewDue, 0);
  const units = data?.whatChanged?.units ?? [];
  const unchosen = units.filter((u) => !u.chosen);
  const shown = showAll ? units : units.slice(0, FIRST_ROWS);

  return (
    <>
      {week && <div className="dashboard"><ThisWeek week={week} /></div>}
      {children}
      <div className="dashboard">
        {total > 0 && (
          <p className="hint">
            {done} of {total} units started across {coverage.length === 1 ? "1 area" : `${coverage.length} areas`}
            {reviewDue > 0 && <>, {reviewDue} due for review</>}.{" "}
            <Link to="/progress">See progress by area</Link>
          </p>
        )}
        {data && data.weakAreas.length > 0 && (
          <section className="card" aria-label="Weak areas">
            <h3>Weakest areas with something to learn</h3>
            <ul className="units">
              {data.weakAreas.map((w) => (
                <li key={w.topicId}>
                  <Link to={`/topics/${w.topicId}`}>{w.name}</Link>
                  <span className="count">{w.domainName} · {w.strength.toFixed(1)}/5 · {w.unitsLeft} to learn</span>
                </li>
              ))}
            </ul>
          </section>
        )}
        {data?.whatChanged && units.length > 0 && (
          <section className="card" aria-label="What changed">
            <h3 ref={changedHeading}>What changed in {data.whatChanged.version}</h3>
            <p className="hint">
              Released {formatMoment(data.whatChanged.releasedAt)}: {data.whatChanged.added} new,{" "}
              {data.whatChanged.changed} updated, {data.whatChanged.retired} retired. Choose when each new unit joins
              your plan.
            </p>
            {unchosen.length > 0 && (
              <p>
                <button type="button" className="link" disabled={busy}
                  onClick={() => acceptAll(unchosen.map((u) => u.unitId))}>
                  {unchosen.length === 1 ? "Accept the suggested placement" : `Accept all ${unchosen.length} suggested placements`}
                </button>
              </p>
            )}
            <ul className="placements" ref={placements}>
              {shown.map((u) => (
                <li key={u.unitId}>
                  <span className="placement-unit">
                    <Link to={`/units/${u.unitId}`}>{u.title}</Link>
                    <span className="count">
                      {TYPE_LABEL[u.type] ?? u.type} · {u.added ? "new" : "updated"}
                      {!u.chosen && <> · suggested: {PLACEMENT[u.suggested]}</>}
                    </span>
                  </span>
                  <span role="group" aria-label={`When to start ${u.title}`} className="choices">
                    {(Object.keys(PLACEMENT) as Placement[]).map((p) => (
                      <button key={p} className={u.placement === p ? "chosen" : ""} aria-pressed={u.placement === p}
                        onClick={() => u.placement !== p && place(u.unitId, p)}>
                        {PLACEMENT[p]}
                      </button>
                    ))}
                  </span>
                </li>
              ))}
            </ul>
            {shown.length < units.length && (
              <p>
                <button type="button" className="link" onClick={revealAll}>Show all {units.length}</button>
              </p>
            )}
            {data.whatChanged.changelog && (
              <details>
                <summary>Release notes</summary>
                <Markdown>{data.whatChanged.changelog}</Markdown>
              </details>
            )}
            {error && <p role="alert">{error}</p>}
          </section>
        )}
      </div>
    </>
  );
}

function ThisWeek({ week }: { week: Week }) {
  if (week.onBreak) {
    return <section className="card" aria-label="This week"><h3>This week</h3><p>A planned break. Enjoy it.</p></section>;
  }
  if (!week.plan) {
    return (
      <section className="card" aria-label="This week">
        <h3>This week</h3>
        <p><Link to="/week">Tell the planner how much time you have</Link> and it will lay out your week.</p>
      </section>
    );
  }
  const plan = week.plan;
  const today = ((new Date().getDay() + 6) % 7) + 1;
  const next = plan.items.filter((i) => !i.done && i.day >= today);
  const day = next[0]?.day;
  const percent = plan.goalMinutes === 0 ? 0 : Math.min(100, Math.round((plan.doneMinutes / plan.goalMinutes) * 100));
  return (
    <section className="card goal" aria-label="This week">
      <h3>This week</h3>
      <div className="bar"><div style={{ width: `${percent}%` }} /></div>
      <p className="hint">{plan.doneMinutes} of {plan.goalMinutes} goal minutes done.</p>
      {day ? (
        <>
          <p>{day === today ? "Today" : "Next up"}:</p>
          <ul className="units">
            {next.filter((i) => i.day === day).map((i) => (
              <li key={itemKey(i)}>
                <Link to={itemHref(i)}>{i.title}</Link>
                <span className="count">{itemLabel(i)} · {i.minutes} min</span>
              </li>
            ))}
          </ul>
        </>
      ) : (
        <p>Everything planned for this week is done.</p>
      )}
      <p><Link to="/week">Open this week's plan</Link></p>
    </section>
  );
}
