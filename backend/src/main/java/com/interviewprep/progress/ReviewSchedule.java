package com.interviewprep.progress;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * When a unit is next due for review, worked out from how each attempt at it went.
 *
 * <p>The ladder: 3, 7, 16, 35 and 75 days (the proposal's F6 started at 1 day; that gave a unit
 * learned this week up to three reviews in two weeks, which crowded the plan). "Good" climbs one
 * step, "easy" two (three on a first attempt, so it lands on 16 days), "hard" repeats the current
 * step and "again" starts over. Past the top, each good review roughly doubles the gap, up to a year.
 *
 * <p>ponytail: a fixed ladder, not FSRS. It needs no tuning data, which two learners will not have
 * for months; switch to FSRS if reviews start feeling badly timed.
 *
 * <p>Public so that anything else a learner rehearses (their own project questions) spaces out on
 * exactly the same ladder, rather than on a copy of it that drifts.
 */
public final class ReviewSchedule {

  /** The four answers to "how did it go?", the same wherever the question is asked. */
  public static final Set<String> RATINGS = Set.of("again", "hard", "good", "easy");

  static final int[] LADDER = {3, 7, 16, 35, 75};
  private static final int MAX_DAYS = 365;

  /** One rating, and the day (in {@link ProgressQueries#STUDY_ZONE}) it was given. */
  public record Step(String rating, LocalDate on) {}

  private ReviewSchedule() {}

  /** Attempts oldest first; returns null when there are none (the unit was never done). */
  public static LocalDate dueOn(List<Step> attempts) {
    if (attempts.isEmpty()) {
      return null;
    }
    return attempts.getLast().on().plusDays(intervalAfter(attempts));
  }

  /**
   * The gap, in days, between the last attempt and the next review: how far up the ladder the
   * attempts have climbed. Attempts oldest first; there must be at least one.
   */
  public static int intervalAfter(List<Step> attempts) {
    int step = -1;
    for (Step a : attempts) {
      step = switch (a.rating()) {
        case "again" -> 0;
        case "hard" -> Math.max(step, 0);
        case "good" -> step + 1;
        // A first "easy" lands on the third rung (16 days), so something already known does not
        // come back within the week; later "easy" answers climb two rungs.
        case "easy" -> step < 0 ? 2 : step + 2;
        default -> throw new IllegalArgumentException("unknown rating " + a.rating());
      };
    }
    return intervalDays(step);
  }

  static int intervalDays(int step) {
    if (step < LADDER.length) {
      return LADDER[step];
    }
    double days = LADDER[LADDER.length - 1] * Math.pow(2.2, step - (LADDER.length - 1));
    return (int) Math.min(MAX_DAYS, Math.round(days));
  }
}
