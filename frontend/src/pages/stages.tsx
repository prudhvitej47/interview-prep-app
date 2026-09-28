import type { Stage, StageCounts } from "../api";

/**
 * The four stages a unit or project question can be at, as the server decides them (Stage.java).
 * Every place that shows progress uses these words, so "solid" means the same on every page.
 */
export const STAGES: Stage[] = ["solid", "learned", "review-due", "not-started"];

export const STAGE_LABEL: Record<Stage, string> = {
  "not-started": "Not started",
  learned: "Learned",
  "review-due": "Review due",
  solid: "Solid",
};

const COUNT_KEY: Record<Stage, keyof StageCounts> = {
  "not-started": "notStarted",
  learned: "learned",
  "review-due": "reviewDue",
  solid: "solid",
};

export const countOf = (counts: StageCounts, stage: Stage) => counts[COUNT_KEY[stage]];
export const totalOf = (c: StageCounts) => c.notStarted + c.learned + c.reviewDue + c.solid;

/**
 * "2 solid, 3 learned, 1 review due, 10 not started": the bar in words, for anyone not seeing its
 * colours. The visible line skips the stages at zero; the bar's label keeps all four.
 */
export function describeCounts(c: StageCounts, skipZeros = false) {
  return STAGES.filter((s) => !skipZeros || countOf(c, s) > 0)
    .map((s) => `${countOf(c, s)} ${STAGE_LABEL[s].toLowerCase()}`).join(", ");
}

/**
 * One unit's status in words. A unit reviewed at least once and not yet solid says how often, which
 * is the part of "learned" that shows effort; a due one says so first, because that is what to act on.
 */
export function StageChip({ stage, reviews, notForMe = false }: { stage: Stage; reviews: number; notForMe?: boolean }) {
  if (notForMe) return <span className="stage-chip not-for-me">Not for me</span>;
  if (stage === "learned" && reviews >= 1) {
    return (
      <span className="stage-chip learned">
        Reviewed <span aria-hidden="true">×{reviews}</span>
        <span className="visually-hidden">{reviews === 1 ? "once" : `${reviews} times`}</span>
      </span>
    );
  }
  return <span className={`stage-chip ${stage}`}>{STAGE_LABEL[stage]}</span>;
}

/**
 * A bar split by stage: solid, then learned, then due, with the unstarted rest left as the empty
 * track. Colour follows the house rule (green for progress, amber for attention); the counts are
 * always also given in words, as the image's label and in the text beside it.
 */
export function StageBar({ counts, label }: { counts: StageCounts; label: string }) {
  const total = totalOf(counts);
  return (
    <span className="stage-bar" role="img" aria-label={`${label}: ${describeCounts(counts)}`}>
      {total > 0 &&
        STAGES.filter((s) => s !== "not-started" && countOf(counts, s) > 0).map((s) => (
          <span key={s} className={s} style={{ width: `${(countOf(counts, s) / total) * 100}%` }} />
        ))}
    </span>
  );
}

/** What the bar's colours mean, once per card or page. */
export function StageLegend() {
  return (
    <ul className="stage-legend" aria-label="Key">
      {STAGES.map((s) => (
        <li key={s}>
          <span className={`swatch ${s}`} aria-hidden="true" />
          {STAGE_LABEL[s]}
        </li>
      ))}
    </ul>
  );
}
