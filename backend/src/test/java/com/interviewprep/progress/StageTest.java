package com.interviewprep.progress;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewprep.progress.ReviewSchedule.Step;
import com.interviewprep.progress.Stage.Standing;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StageTest {

  private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

  /** One attempt a day from DAY; the last is on DAY + (n - 1). */
  private static List<Step> steps(String... ratings) {
    List<Step> steps = new ArrayList<>();
    for (int i = 0; i < ratings.length; i++) {
      steps.add(new Step(ratings[i], DAY.plusDays(i)));
    }
    return steps;
  }

  /** The stage the day after the last attempt: nothing on the ladder is due that soon. */
  private static Standing dayAfter(String... ratings) {
    return Stage.of(steps(ratings), DAY.plusDays(ratings.length));
  }

  /** The stage on the day of the last attempt, when nothing just rated can be due. */
  private static Standing sameDay(String... ratings) {
    return Stage.of(steps(ratings), DAY.plusDays(ratings.length - 1));
  }

  @Test
  void neverAttemptedIsNotStarted() {
    assertThat(Stage.of(List.of(), DAY)).isEqualTo(new Standing(Stage.NOT_STARTED, 0, null));
  }

  @Test
  void aFirstAttemptIsLearnedWithNoReviews() {
    Standing s = sameDay("good");
    assertThat(s.stage()).isEqualTo(Stage.LEARNED);
    assertThat(s.reviews()).isZero();
    assertThat(s.dueOn()).isEqualTo(DAY.plusDays(3));
  }

  @Test
  void dueTodayIsReviewDueAndSoIsOverdue() {
    // One good: due three days later.
    assertThat(Stage.of(steps("good"), DAY.plusDays(3)).stage()).isEqualTo(Stage.REVIEW_DUE);
    assertThat(Stage.of(steps("good"), DAY.plusDays(9)).stage()).isEqualTo(Stage.REVIEW_DUE);
    // The day before it falls due it is still learned (second attempt on DAY + 1, a 7-day gap).
    assertThat(Stage.of(steps("good", "good"), DAY.plusDays(7)).stage()).isEqualTo(Stage.LEARNED);
    assertThat(Stage.of(steps("good", "good"), DAY.plusDays(8)).stage()).isEqualTo(Stage.REVIEW_DUE);
  }

  @Test
  void theThirdStepIsStillLearnedAndTheFourthIsSolid() {
    // good, good, good: a 16-day gap.
    assertThat(dayAfter("good", "good", "good").stage()).isEqualTo(Stage.LEARNED);
    // A fourth good reaches exactly 35 days: solid.
    Standing solid = dayAfter("good", "good", "good", "good");
    assertThat(solid.stage()).isEqualTo(Stage.SOLID);
    assertThat(solid.reviews()).isEqualTo(3);
    assertThat(solid.dueOn()).isEqualTo(DAY.plusDays(3 + 35));
  }

  @Test
  void aSolidUnitWhoseReviewComesRoundIsDue() {
    List<Step> s = steps("good", "good", "good", "good");
    assertThat(Stage.of(s, DAY.plusDays(3 + 34)).stage()).isEqualTo(Stage.SOLID);
    assertThat(Stage.of(s, DAY.plusDays(3 + 35)).stage()).isEqualTo(Stage.REVIEW_DUE);
  }

  @Test
  void againStartsOverSoASolidUnitIsLearnedAgain() {
    // again: due three days later, so it is not due the day after, and it is learned, not solid.
    assertThat(dayAfter("good", "good", "good", "good", "again").stage()).isEqualTo(Stage.LEARNED);
    assertThat(Stage.of(steps("good", "good", "good", "good", "again"), DAY.plusDays(4 + 3)).stage())
        .isEqualTo(Stage.REVIEW_DUE);
    assertThat(sameDay("good", "good", "good", "good", "again").stage()).isEqualTo(Stage.LEARNED);
    assertThat(sameDay("good", "good", "good", "good", "again").reviews()).isEqualTo(4);
  }

  @Test
  void hardRepeatsTheStepSoItNeverClimbsToSolid() {
    assertThat(sameDay("good", "good", "good", "hard", "hard", "hard").stage()).isEqualTo(Stage.LEARNED);
    // Hard on the 35-day step keeps it there: still solid.
    assertThat(sameDay("good", "good", "good", "good", "hard").stage()).isEqualTo(Stage.SOLID);
  }

  @Test
  void easyJumpsTwoStepsSoFewerReviewsReachSolid() {
    // easy (16 days), easy (75 days): solid after one review.
    Standing s = sameDay("easy", "easy");
    assertThat(s.stage()).isEqualTo(Stage.SOLID);
    assertThat(s.reviews()).isEqualTo(1);
    // A first easy alone is the 16-day step: learned. Easy then good is the 35-day step: solid.
    assertThat(sameDay("easy").stage()).isEqualTo(Stage.LEARNED);
    assertThat(sameDay("easy", "good").stage()).isEqualTo(Stage.SOLID);
  }

  @Test
  void theThresholdIsTheLaddersFourthStep() {
    assertThat(Stage.SOLID_DAYS).isEqualTo(35);
    assertThat(Stage.REVIEW_DUE.id()).isEqualTo("review-due");
    assertThat(Stage.NOT_STARTED.id()).isEqualTo("not-started");
  }
}
