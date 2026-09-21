package com.interviewprep.progress;

import java.time.LocalDate;
import java.util.List;

/**
 * When a unit is next due for review, worked out from how each attempt at it went.
 *
 * <p>The ladder from the proposal (F6): roughly 1, 3, 7, 16 and 35 days, stretched or shrunk by the
 * rating. "Good" climbs one step, "easy" two, "hard" repeats the current step and "again" starts
 * over. Past the top, each good review roughly doubles the gap, up to a year.
 *
 * <p>ponytail: a fixed ladder, not FSRS. It needs no tuning data, which two learners will not have
 * for months; switch to FSRS if reviews start feeling badly timed.
 */
final class ReviewSchedule {

  static final int[] LADDER = {1, 3, 7, 16, 35};
  private static final int MAX_DAYS = 365;

  record Step(String rating, LocalDate on) {}

  private ReviewSchedule() {}

  /** Attempts oldest first; returns null when there are none (the unit was never done). */
  static LocalDate dueOn(List<Step> attempts) {
    if (attempts.isEmpty()) {
      return null;
    }
    int step = -1;
    for (Step a : attempts) {
      step = switch (a.rating()) {
        case "again" -> 0;
        case "hard" -> Math.max(step, 0);
        case "good" -> step + 1;
        case "easy" -> step + 2;
        default -> throw new IllegalArgumentException("unknown rating " + a.rating());
      };
    }
    return attempts.getLast().on().plusDays(intervalDays(step));
  }

  static int intervalDays(int step) {
    if (step < LADDER.length) {
      return LADDER[step];
    }
    double days = LADDER[LADDER.length - 1] * Math.pow(2.2, step - (LADDER.length - 1));
    return (int) Math.min(MAX_DAYS, Math.round(days));
  }
}
