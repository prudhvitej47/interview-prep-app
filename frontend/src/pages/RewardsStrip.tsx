import { useEffect, useState } from "react";
import { fetchRewards, type Rewards, type WeekOutcome } from "../api";
import { formatDay } from "../unit/ProgressPanel";

// One mark per week, oldest first: quiet, no leaderboard, nothing framed as a loss.
const MARK: Record<WeekOutcome, { mark: string; label: string }> = {
  GOAL_MET: { mark: "●", label: "goal met" },
  FREEZE_USED: { mark: "❄", label: "missed, a freeze kept the streak" },
  MISSED: { mark: "○", label: "goal not reached" },
  BREAK: { mark: "–", label: "planned break" },
  IN_PROGRESS: { mark: "◌", label: "this week, in progress" },
};

// The same five outcomes as a sentence, for when there is only one week to report.
function inWords(week: { weekStart: string; outcome: WeekOutcome }): string {
  return week.outcome === "IN_PROGRESS"
    ? "This week: in progress"
    : `Week of ${formatDay(week.weekStart)}: ${MARK[week.outcome].label}`;
}

export function RewardsStrip() {
  const [rewards, setRewards] = useState<Rewards | null>(null);

  useEffect(() => {
    fetchRewards().then(setRewards).catch(() => setRewards(null));
  }, []);

  if (!rewards) return null;
  const milestone = rewards.milestonesReached.at(-1);
  return (
    <section className="rewards" aria-label="Stars and streak">
      <p>
        <strong>★ {rewards.stars}</strong> star{rewards.stars === 1 ? "" : "s"}
        {rewards.starsThisWeek > 0 && <span className="count"> (+{rewards.starsThisWeek} this week)</span>}
        {" · "}
        <strong>{rewards.streak}</strong>-week streak
        {" · "}
        {rewards.freezes} freeze{rewards.freezes === 1 ? "" : "s"} banked
      </p>
      {/* One week is not a row, and a single mark followed by a key explaining that one mark read
          as a stammer ("Recent weeks ◌ ◌ this week, in progress"). Below two weeks there is nothing
          to compare, so the week says what it is in plain words. */}
      {rewards.recentWeeks.length === 1 && <p className="count">{inWords(rewards.recentWeeks[0])}</p>}
      {rewards.recentWeeks.length > 1 && (
        <>
          <p className="weeks-row">
            <span className="count">Recent weeks</span>{" "}
            <span className="weeks" aria-label="Recent weeks">
              {rewards.recentWeeks.map((w) => (
                <span key={w.weekStart} title={`Week of ${formatDay(w.weekStart)}: ${MARK[w.outcome].label}`}
                  aria-label={`Week of ${formatDay(w.weekStart)}: ${MARK[w.outcome].label}`}>
                  {MARK[w.outcome].mark}
                </span>
              ))}
            </span>
          </p>
          {/* A key for the marks actually shown, so no mark is a mystery - on its own line, because
              at twelve weeks it is longer than the row it explains. */}
          <p className="count">
            {[...new Set(rewards.recentWeeks.map((w) => w.outcome))]
              .map((o) => `${MARK[o].mark} ${MARK[o].label}`).join(" · ")}
          </p>
        </>
      )}
      {milestone && <p className="hint">You have passed {milestone} stars.</p>}
    </section>
  );
}
