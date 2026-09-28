package com.interviewprep.progress;

import java.time.LocalDate;
import java.util.List;

/**
 * Where a learner stands with one thing they rehearse (a unit, or a question about their own project),
 * read off the review ladder. One definition, used by every page that shows progress:
 *
 * <ul>
 *   <li>{@code not-started}: never attempted;
 *   <li>{@code review-due}: attempted, and the next review falls today or has passed;
 *   <li>{@code learned}: attempted, not due, and the gap to the next review is under {@link
 *       #SOLID_DAYS} days: still low on the ladder;
 *   <li>{@code solid}: attempted, not due, and the gap is {@link #SOLID_DAYS} days or more: the
 *       ladder's fourth step, reached only by surviving several spaced reviews.
 * </ul>
 *
 * <p>Due comes before solid: a solid unit whose review has come round is due, like any other, until
 * it is reviewed. An "again" drops a unit back to the bottom of the ladder, so back to learned.
 *
 * <p>ponytail: a threshold on the current gap, not a memory model. It needs no data, is easy to
 * explain on one line of How this works, and agrees with what the review queue shows.
 */
public enum Stage {
  NOT_STARTED("not-started"),
  REVIEW_DUE("review-due"),
  LEARNED("learned"),
  SOLID("solid");

  /** The gap that makes something solid: the ladder's fourth step (1, 3, 7, 16 days). */
  public static final int SOLID_DAYS = ReviewSchedule.LADDER[3];

  private final String id;

  Stage(String id) {
    this.id = id;
  }

  /** The name the API and the browser use. */
  public String id() {
    return id;
  }

  /**
   * A stage with what it rests on. {@code reviews} counts attempts after the first; {@code dueOn} is
   * null when never attempted.
   */
  public record Standing(Stage stage, int reviews, LocalDate dueOn) {}

  /** From the attempts, oldest first, as of {@code today} (a day in India time). */
  public static Standing of(List<ReviewSchedule.Step> attempts, LocalDate today) {
    if (attempts.isEmpty()) {
      return new Standing(NOT_STARTED, 0, null);
    }
    LocalDate dueOn = ReviewSchedule.dueOn(attempts);
    int reviews = attempts.size() - 1;
    if (!dueOn.isAfter(today)) {
      return new Standing(REVIEW_DUE, reviews, dueOn);
    }
    Stage stage = ReviewSchedule.intervalAfter(attempts) >= SOLID_DAYS ? SOLID : LEARNED;
    return new Standing(stage, reviews, dueOn);
  }
}
