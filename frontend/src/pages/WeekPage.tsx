import { useEffect, useState } from "react";
import { Link } from "react-router";
import {
  fetchBreaks, fetchDomains, fetchWeek, giveBackBreak, rateTopics, saveWeekSettings, takeBreak,
  type Breaks, type Domain, type PlanView, type Week, type WeekSettings,
} from "../api";
import { formatDay } from "../unit/ProgressPanel";
import { TYPE_LABEL } from "../unit/sections";

const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

/** The date of a plan day (1 = Monday), computed in local time so no UTC shift can move it. */
const dayDate = (weekStart: string, day: number) => {
  const d = new Date(`${weekStart}T00:00:00`);
  d.setDate(d.getDate() + day - 1);
  return formatDay(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`);
};

export function WeekPage() {
  const [week, setWeek] = useState<Week | null>(null);
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchWeek().then(setWeek).catch((e: Error) => setError(e.message));
  }, []);

  if (error) return <p role="alert">{error}</p>;
  if (!week) return <p>Loading…</p>;

  return (
    <article className="week">
      <nav className="crumbs" aria-label="Breadcrumb">
        <Link to="/">Home</Link> › This week
      </nav>
      <h2>Week of {formatDay(week.weekStart)}</h2>
      {week.onBreak ? (
        <p className="banner" role="note">This week is a planned break: no plan, and your streak is safe.</p>
      ) : !week.plan || editing ? (
        <SettingsForm
          initial={week.settings}
          firstTime={!week.plan}
          onSaved={(w) => {
            setWeek(w);
            setEditing(false);
          }}
          onCancel={week.plan ? () => setEditing(false) : undefined}
        />
      ) : (
        <>
          <Plan plan={week.plan} weekStart={week.weekStart} />
          <TopicCard plan={week.plan} />
          <p>
            <button className="link" onClick={() => setEditing(true)}>Change my hours, days or weights</button>
          </p>
        </>
      )}
      <PlannedBreaks />
    </article>
  );
}

function Plan({ plan, weekStart }: { plan: PlanView; weekStart: string }) {
  const days = [...new Set(plan.items.map((i) => i.day))].sort();
  const percent = plan.goalMinutes === 0 ? 0 : Math.min(100, Math.round((plan.doneMinutes / plan.goalMinutes) * 100));
  return (
    <>
      <section className="goal" aria-label="This week's goal">
        <div className="bar"><div style={{ width: `${percent}%` }} /></div>
        {plan.goalMinutes > 0 && plan.doneMinutes >= plan.goalMinutes && (
          <p className="verdict right" role="status">
            ✓ Goal met: this week counts towards your streak{plan.doneMinutes >= plan.plannedMinutes && ", and the whole plan is done"}.
          </p>
        )}
        <p className="hint">
          {plan.doneMinutes} of {plan.goalMinutes} goal minutes done ({plan.plannedMinutes} planned; the goal leaves room
          for a bad day). An item counts once you mark it done or record its review.
        </p>
      </section>

      {plan.items.length === 0 && <p>Nothing to plan yet: the curriculum has no units ready for you.</p>}
      {days.map((day) => (
        <section key={day} className="day">
          <h3>{dayDate(weekStart, day)}</h3>
          <ul>
            {plan.items.filter((i) => i.day === day).map((i) => (
              <li key={`${i.kind}-${i.unitId}`} className={i.done ? "done" : ""}>
                <div className="item-head">
                  <span aria-label={i.done ? "done" : "to do"}>{i.done ? "✓" : "○"}</span>
                  <Link to={`/units/${i.unitId}`}>{i.title}</Link>
                  <span className="count">
                    {i.kind === "review" ? "Review" : TYPE_LABEL[i.type] ?? i.type} · {i.minutes} min
                  </span>
                </div>
                <p className="hint">{i.reason}</p>
              </li>
            ))}
          </ul>
        </section>
      ))}

      <details className="why">
        <summary>How this week is split</summary>
        <ul className="shares">
          {plan.shares.map((s) => (
            <li key={s.domainId}>
              <span>{s.name}</span>
              <span className="share-bar"><span style={{ width: `${s.percent}%` }} /></span>
              <span className="count">{s.percent}%</span>
            </li>
          ))}
        </ul>
        <p className="hint">
          Each area's share starts from its weight and grows when you are weaker in it, by at most half again.
          Topics you are weakest in come first within an area.
        </p>
        {plan.notes.length > 0 && (
          <ul>{plan.notes.map((n) => <li key={n} className="hint">{n}</li>)}</ul>
        )}
      </details>
    </>
  );
}

/** The weekly "how are you with these?" card. Optional: it sharpens next week, never blocks this one. */
function TopicCard({ plan }: { plan: PlanView }) {
  const [ratings, setRatings] = useState<Record<string, number>>({});
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (plan.topicsToRate.length === 0) return null;
  if (saved) return <p className="hint" role="status">Thanks. Next week's plan will use these.</p>;
  return (
    <section className="topic-card">
      <h3>How are you with these?</h3>
      <p className="hint">
        This week teaches them, and so far their strength is a guess from your area rating. Optional; it sharpens next
        week's plan.
      </p>
      {plan.topicsToRate.map((t) => (
        <fieldset key={t.topicId}>
          <legend>
            {t.name} <span className="count">{t.domainName} · guessed {t.currentGuess}/5</span>
          </legend>
          {[0, 1, 2, 3, 4, 5].map((v) => (
            <label key={v}>
              <input type="radio" name={t.topicId} checked={ratings[t.topicId] === v}
                onChange={() => setRatings({ ...ratings, [t.topicId]: v })} />
              {v}
            </label>
          ))}
        </fieldset>
      ))}
      <div className="note-actions">
        <button disabled={Object.keys(ratings).length === 0}
          onClick={() => rateTopics(ratings).then(() => setSaved(true)).catch((e: Error) => setError(e.message))}>
          Save ratings
        </button>
      </div>
      {error && <p role="alert">{error}</p>}
    </section>
  );
}

function SettingsForm({ initial, firstTime, onSaved, onCancel }: {
  initial: WeekSettings;
  firstTime: boolean;
  onSaved: (w: Week) => void;
  onCancel?: () => void;
}) {
  const [hours, setHours] = useState(initial.hoursPerWeek?.toString() ?? "");
  const [days, setDays] = useState<number[]>(initial.studyDays);
  const [weights, setWeights] = useState<Record<string, string>>(
    Object.fromEntries(Object.entries(initial.weights).map(([k, v]) => [k, String(v)])));
  const [domains, setDomains] = useState<Domain[]>([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchDomains().then(setDomains).catch(() => setDomains([]));
  }, []);

  const hoursValue = Number(hours);
  const valid = hours !== "" && hoursValue >= 1 && hoursValue <= 40 && days.length > 0;

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const overrides = Object.fromEntries(
        Object.entries(weights).filter(([, v]) => v.trim() !== "").map(([k, v]) => [k, Number(v)]));
      onSaved(await saveWeekSettings({ hoursPerWeek: hoursValue, studyDays: days, weights: overrides }));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className="settings" onSubmit={save}>
      {firstTime && <p>Tell the planner how much time you have, and it will lay out this week.</p>}
      <label>
        Hours a week <input type="number" min={1} max={40} step={0.5} value={hours} onChange={(e) => setHours(e.target.value)} />
      </label>
      <fieldset>
        <legend>Study days</legend>
        {DAYS.map((name, i) => (
          <label key={name}>
            <input type="checkbox" checked={days.includes(i + 1)}
              onChange={() => setDays(days.includes(i + 1) ? days.filter((d) => d !== i + 1) : [...days, i + 1].sort())} />
            {name}
          </label>
        ))}
      </fieldset>
      <details>
        <summary>Weights by area (optional)</summary>
        <p className="hint">Blank uses the curriculum's default. They are relative: 20 gets twice the time of 10. 0 leaves an area out.</p>
        {domains.map((d) => (
          <label key={d.id} className="weight">
            <span>{d.name}</span>
            <input type="number" min={0} max={100} placeholder={String(d.weight)} value={weights[d.id] ?? ""}
              onChange={(e) => setWeights({ ...weights, [d.id]: e.target.value })} aria-label={`Weight for ${d.name}`} />
          </label>
        ))}
      </details>
      <div className="note-actions">
        <button type="submit" disabled={!valid || saving}>{firstTime ? "Plan my week" : "Save and rebuild this week"}</button>
        {onCancel && <button type="button" className="secondary" onClick={onCancel}>Cancel</button>}
      </div>
      {!firstTime && <p className="hint">Rebuilding keeps everything you have done; only the plan is redrawn.</p>}
      {error && <p role="alert">{error}</p>}
    </form>
  );
}

/** Up to two weeks a quarter, taken before the week starts (proposal G). */
function PlannedBreaks() {
  const [breaks, setBreaks] = useState<Breaks | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchBreaks().then(setBreaks).catch(() => setBreaks(null));
  }, []);

  if (!breaks) return null;
  const taken = breaks.upcoming.filter((b) => b.taken).length;
  const act = (call: Promise<Breaks>) => call.then(setBreaks).catch((e: Error) => setError(e.message));
  return (
    <details className="breaks">
      <summary>Planned breaks{taken > 0 && <span className="count"> {taken} coming up</span>}</summary>
      <p className="hint">
        Travelling, or a release crunch? Take a week off before it starts: it gets no plan and neither counts towards
        nor breaks your streak. Up to two a quarter.
      </p>
      <ul>
        {breaks.upcoming.map((b) => (
          <li key={b.weekStart}>
            <span>Week of {formatDay(b.weekStart)}</span>
            {b.taken ? (
              <button className="link" onClick={() => act(giveBackBreak(b.weekStart))}>Give it back</button>
            ) : b.available ? (
              <button className="link" onClick={() => act(takeBreak(b.weekStart))}>Take it off</button>
            ) : (
              <span className="count">two already taken this quarter</span>
            )}
            {b.taken && <span className="count"> · break</span>}
          </li>
        ))}
      </ul>
      {error && <p role="alert">{error}</p>}
    </details>
  );
}
