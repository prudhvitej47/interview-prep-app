import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { fetchProgressView, type ProgressView, type Stage } from "../api";
import { RUNG_LABEL } from "../projects/rungs";
import { formatDay } from "../unit/ProgressPanel";
import { TYPE_LABEL } from "../unit/sections";
import { describeCounts, STAGE_LABEL, StageBar, StageChip, StageLegend, STAGES, totalOf } from "./stages";

/** When a unit or question is next reviewed, or since when it has been due. */
function when(stage: Stage, dueOn: string | null, today: string) {
  if (!dueOn) return null;
  if (stage === "review-due") return dueOn === today ? "due today" : `due since ${formatDay(dueOn)}`;
  return `next review ${formatDay(dueOn)}`;
}

/**
 * Progress: every area's units by stage, the units themselves filterable by stage and area, and the
 * questions about the learner's own projects. The stages come from the server (Stage.java), from the
 * ratings alone; this page only counts and filters them.
 */
export function ProgressPage() {
  const [data, setData] = useState<ProgressView | null>(null);
  const [error, setError] = useState<string | null>(null);
  // In the address, so Back from a unit returns to the same filtered list.
  const [params, setParams] = useSearchParams();
  const stage = (STAGES as string[]).includes(params.get("stage") ?? "") ? (params.get("stage") as Stage) : "";
  const area = params.get("area") ?? "";

  useEffect(() => {
    fetchProgressView().then(setData).catch((e: Error) => setError(e.message));
  }, []);

  const setFilter = (key: "stage" | "area", value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };

  if (error) return <p role="alert">{error}</p>;
  if (!data) return <p>Loading your progress…</p>;

  const units = data.units.filter((u) => !u.notForMe);
  const inArea = units.filter((u) => !area || u.domainId === area);
  const shown = inArea.filter((u) => !stage || u.stage === stage);
  const areaName = new Map(data.areas.map((a) => [a.domainId, a.name]));

  return (
    <article className="progress-page">
      <h2>Progress</h2>
      <p>
        Where you stand with each unit, from how your ratings went. <strong>Learned</strong>: done, next review
        under 16 days away. <strong>Solid</strong>: next review 16 or more days away, so it has held up through
        several reviews. <strong>Review due</strong>: its review is today or overdue.{" "}
        <Link to="/how-it-works#h-progress">More on the stages</Link>
      </p>

      <section aria-labelledby="h-areas">
        <h3 id="h-areas">By area</h3>
        {data.areas.length === 0 ? (
          <p className="empty">No units in your curriculum yet.</p>
        ) : (
          <>
            <StageLegend />
            <ul className="stage-areas">
              {data.areas.map((a) => (
                <li key={a.domainId}>
                  <span className="area-name">{a.name}</span>
                  <StageBar counts={a.stages} label={a.name} />
                  <span className="count">{totalOf(a.stages) - a.stages.notStarted}/{totalOf(a.stages)}</span>
                  <span className="count area-counts">{describeCounts(a.stages)}</span>
                </li>
              ))}
            </ul>
          </>
        )}
        {data.notForMe > 0 && (
          <p className="hint">
            {data.notForMe === 1 ? "1 unit" : `${data.notForMe} units`} you kept out of your plans ("not for me"){" "}
            {data.notForMe === 1 ? "is" : "are"} left out here.
          </p>
        )}
      </section>

      <section aria-labelledby="h-units">
        <h3 id="h-units">Units</h3>
        <div className="filters">
          <label className="filter">
            Stage{" "}
            <select value={stage} onChange={(e) => setFilter("stage", e.target.value)}>
              <option value="">All ({inArea.length})</option>
              {STAGES.map((s) => (
                <option key={s} value={s}>
                  {STAGE_LABEL[s]} ({inArea.filter((u) => u.stage === s).length})
                </option>
              ))}
            </select>
          </label>
          <label className="filter">
            Area{" "}
            <select value={area} onChange={(e) => setFilter("area", e.target.value)}>
              <option value="">All areas</option>
              {data.areas.map((a) => <option key={a.domainId} value={a.domainId}>{a.name}</option>)}
            </select>
          </label>
        </div>
        {shown.length === 0 ? (
          <p className="empty">No units match.</p>
        ) : (
          <ul className="units stage-units">
            {shown.map((u) => (
              <li key={u.unitId}>
                <span className="unit-line">
                  <Link to={`/units/${u.unitId}`}>{u.title}</Link>
                  <StageChip stage={u.stage} reviews={u.reviews} />
                </span>
                <span className="meta">
                  {[areaName.get(u.domainId), u.topicName, TYPE_LABEL[u.type] ?? u.type,
                    when(u.stage, u.dueOn, data.today)].filter(Boolean).join(" · ")}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="h-project-questions">
        <h3 id="h-project-questions">Project questions</h3>
        {data.projects.length === 0 ? (
          <p className="empty">
            No questions yet. Add them on <Link to="/projects">My projects</Link>.
          </p>
        ) : (
          data.projects.map((p) => (
            <div key={p.projectId} className="stage-project">
              <ul className="stage-areas">
                <li>
                  <h4 className="area-name">
                    <Link to={`/projects/${p.projectId}`}>{p.name}</Link>
                  </h4>
                  <StageBar counts={p.stages} label={p.name} />
                  <span className="count">{totalOf(p.stages) - p.stages.notStarted}/{totalOf(p.stages)}</span>
                  <span className="count area-counts">{describeCounts(p.stages)}</span>
                </li>
              </ul>
              <ul className="units stage-units">
                {p.questions.map((q) => (
                  <li key={q.questionId}>
                    <span className="unit-line">
                      <Link to={`/projects/${p.projectId}#q-${q.questionId}`}>{q.prompt}</Link>
                      <StageChip stage={q.stage} reviews={q.reviews} />
                    </span>
                    <span className="meta">
                      {[RUNG_LABEL[q.rung] ?? q.rung, when(q.stage, q.dueOn, data.today)].filter(Boolean).join(" · ")}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          ))
        )}
      </section>
    </article>
  );
}
