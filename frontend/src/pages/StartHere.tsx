import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import { fetchRewards, fetchWeek, setStartGuideClosed, type Me, type Rewards, type Week } from "../api";

/**
 * The first-visit guide at the top of the home page: what to do first, ticked off as it happens.
 *
 * It shows until the learner closes it, or until the two steps it can check are both done; either
 * way the server remembers, so it never comes back on a later sign-in or another device. "How this
 * works" can bring it back.
 */
export function StartHere({ onMe }: { onMe: (me: Me) => void }) {
  const [week, setWeek] = useState<Week | null>(null);
  const [rewards, setRewards] = useState<Rewards | null>(null);
  const [error, setError] = useState<string | null>(null);
  const recorded = useRef(false);

  useEffect(() => {
    // The steps still read sensibly unticked if either fails to load.
    fetchWeek().then(setWeek).catch(() => setWeek(null));
    fetchRewards().then(setRewards).catch(() => setRewards(null));
  }, []);

  const weekSet = !!week && week.settings.hoursPerWeek !== null && week.settings.studyDays.length > 0;
  // Stars cover every earlier week; a unit rated "again" earns none, so this week's ticks count too.
  const firstUnitDone = (rewards?.stars ?? 0) > 0 || !!week?.plan?.items.some((i) => i.done);
  const allDone = weekSet && firstUnitDone;

  useEffect(() => {
    // Set up: record it once, but leave the card up for this visit so the learner sees why it goes.
    if (allDone && !recorded.current) {
      recorded.current = true;
      setStartGuideClosed(true).catch(() => (recorded.current = false));
    }
  }, [allDone]);

  async function close() {
    setError(null);
    try {
      onMe(await setStartGuideClosed(true));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <section className="card start-here" aria-label="Start here">
      <h3>Start here</h3>
      {allDone ? (
        <p>You are set up. This guide won't show again; <Link to="/how-it-works">How this works</Link> has the details.</p>
      ) : (
        <p className="hint">Four steps to your first week. This card goes away once the first two are done, or when you close it.</p>
      )}
      <ol>
        <Step done={weekSet}>
          <Link to="/week">Set up your week</Link>: how many hours, which days, and, if you like, how much each
          area matters to you (weights). The plan is built from these.
        </Step>
        <Step done={firstUnitDone}>
          Open <Link to="/week">this week's plan</Link> and do today's first unit. At the bottom of its page, say
          how it went: Again, Hard, Good or Easy. That marks it done and schedules its reviews.
        </Step>
        <Step>
          Come back on your study days. Reviews that are due show at the top of this page; the plan shows what
          is next. Stars and the weekly streak count what you finish.
        </Step>
        <Step>
          When new units arrive, the <strong>What changed</strong> card below lets you choose when each one joins
          your plan: now, next week or later.
        </Step>
      </ol>
      <p>
        <Link to="/how-it-works">How this works</Link>
        {" · "}
        <button className="link" onClick={close}>{allDone ? "Close" : "Close this guide"}</button>
      </p>
      {error && <p role="alert">{error}</p>}
    </section>
  );
}

/**
 * One numbered step. The list already numbers every step, so a second marker on every line read as
 * a stray bullet: only a step that is actually done shows anything, and what it shows is a tick
 * after the text. Nothing sits between the number and the text, so the text starts where the
 * number ends on every row. The two informational steps - which can never be ticked - show nothing
 * and say nothing about being outstanding, which is what tells them apart from a step still waiting.
 */
function Step({ done, children }: { done?: boolean; children: React.ReactNode }) {
  return (
    <li className={done ? "done" : undefined}>
      <span className="step-text">{children}</span>
      {done === true && <span className="tick" aria-label="done">✓</span>}
      {done === false && <span className="visually-hidden">to do</span>}
    </li>
  );
}
