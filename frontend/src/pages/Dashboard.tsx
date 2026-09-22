import { useEffect, useState } from "react";
import { Link } from "react-router";
import { fetchDashboard, fetchWeek, placeUnit, type Dashboard as Data, type Placement, type Week } from "../api";
import { Markdown } from "../unit/Markdown";
import { formatMoment } from "../unit/ProgressPanel";
import { TYPE_LABEL } from "../unit/sections";

const PLACEMENT: Record<Placement, string> = { now: "Now", "next-week": "Next week", "end-of-track": "Later" };

/** The top of the home page: this week, coverage, weak areas and what the last release changed. */
export function Dashboard() {
  const [data, setData] = useState<Data | null>(null);
  const [week, setWeek] = useState<Week | null>(null);
  const [error, setError] = useState<string | null>(null);

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

  return (
    <div className="dashboard">
      {week && <ThisWeek week={week} />}
      {data && data.coverage.length > 0 && (
        <section className="card" aria-label="Coverage">
          <h3>Coverage</h3>
          <ul className="shares">
            {data.coverage.map((c) => (
              <li key={c.domainId}>
                <span>{c.name}</span>
                <span className="share-bar"><span style={{ width: `${(c.done / c.total) * 100}%` }} /></span>
                <span className="count">{c.done}/{c.total}</span>
              </li>
            ))}
          </ul>
          <p className="hint">Units done out of those in the curriculum so far, by area.</p>
        </section>
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
      {data?.whatChanged && data.whatChanged.units.length > 0 && (
        <section className="card" aria-label="What changed">
          <h3>What changed in {data.whatChanged.version}</h3>
          <p className="hint">
            Released {formatMoment(data.whatChanged.releasedAt)}: {data.whatChanged.added} new,{" "}
            {data.whatChanged.changed} updated, {data.whatChanged.retired} retired. Choose when each new unit joins your
            plan.
          </p>
          <ul className="placements">
            {data.whatChanged.units.map((u) => (
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
              <li key={`${i.kind}-${i.unitId}`}>
                <Link to={`/units/${i.unitId}`}>{i.title}</Link>
                <span className="count">{i.kind === "review" ? "Review" : TYPE_LABEL[i.type] ?? i.type} · {i.minutes} min</span>
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
