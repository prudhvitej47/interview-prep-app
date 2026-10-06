package com.interviewprep.progress;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewprep.progress.ReviewSchedule.Step;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewScheduleTest {

  private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

  private static LocalDate after(String... ratings) {
    // One attempt a day, so each due date is easy to read off.
    List<Step> steps = new java.util.ArrayList<>();
    for (int i = 0; i < ratings.length; i++) {
      steps.add(new Step(ratings[i], DAY.plusDays(i)));
    }
    return ReviewSchedule.dueOn(steps);
  }

  private static long gap(String... ratings) {
    return after(ratings).toEpochDay() - DAY.plusDays(ratings.length - 1).toEpochDay();
  }

  @Test
  void neverDoneMeansNothingIsDue() {
    assertThat(ReviewSchedule.dueOn(List.of())).isNull();
  }

  @Test
  void goodReviewsClimbTheLadder() {
    assertThat(gap("good")).isEqualTo(3);
    assertThat(gap("good", "good")).isEqualTo(7);
    assertThat(gap("good", "good", "good")).isEqualTo(16);
    assertThat(gap("good", "good", "good", "good")).isEqualTo(35);
    assertThat(gap("good", "good", "good", "good", "good")).isEqualTo(75);
    // Past the top the gap roughly doubles.
    assertThat(gap("good", "good", "good", "good", "good", "good")).isEqualTo(165);
  }

  @Test
  void easySkipsAStepHardRepeatsOneAgainStartsOver() {
    assertThat(gap("easy")).isEqualTo(16);  // a first "easy" skips three rungs
    assertThat(gap("good", "easy")).isEqualTo(16);  // later ones skip two: 3 -> 16
    assertThat(gap("again", "easy")).isEqualTo(16);
    assertThat(gap("good", "good", "hard")).isEqualTo(7);
    assertThat(gap("hard")).isEqualTo(3);
    assertThat(gap("good", "good", "good", "again")).isEqualTo(3);
  }

  @Test
  void theGapNeverExceedsAYear() {
    assertThat(ReviewSchedule.intervalDays(30)).isEqualTo(365);
  }
}
