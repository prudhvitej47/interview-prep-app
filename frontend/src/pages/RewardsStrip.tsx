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
      <p className="weeks" aria-label="Recent weeks">
        {rewards.recentWeeks.map((w) => (
          <span key={w.weekStart} title={`Week of ${formatDay(w.weekStart)}: ${MARK[w.outcome].label}`}
            aria-label={`Week of ${formatDay(w.weekStart)}: ${MARK[w.outcome].label}`}>
            {MARK[w.outcome].mark}
          </span>
        ))}
      </p>
      {milestone && <p className="hint">You have passed {milestone} stars.</p>}
    </section>
  );
}
