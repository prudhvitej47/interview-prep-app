package com.interviewprep.gamification;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewprep.gamification.Rewards.Outcome;
import com.interviewprep.gamification.Rewards.Summary;
import com.interviewprep.planner.PlanQueries.WeekResult;
import com.interviewprep.progress.ProgressQueries.Attempt;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RewardsTest {

  private static final LocalDate THIS_MONDAY = LocalDate.of(2026, 10, 26);
  private static final Map<String, String> TYPES = Map.of(
      "concept", "concept", "coding", "coding", "sql", "sql", "hld", "hld", "project", "project",
      "r1", "concept", "r2", "concept", "r3", "concept");

  private static Attempt at(String unit, String rating, LocalDate day) {
    return new Attempt(unit, rating, OffsetDateTime.of(day, LocalTime.NOON, ZoneOffset.ofHoursMinutes(5, 30)));
  }

  private static Summary units(Attempt... attempts) {
    return Rewards.of(List.of(attempts), TYPES, List.of(), Set.of(), THIS_MONDAY);
  }

  /** Weeks ending last week, oldest first: true = goal met, false = missed, null = break. */
  private static Summary weeks(Boolean... outcomes) {
    List<WeekResult> results = new ArrayList<>();
    Set<LocalDate> breaks = new java.util.HashSet<>();
    LocalDate w = THIS_MONDAY.minusWeeks(outcomes.length);
    for (Boolean met : outcomes) {
      if (met == null) {
        breaks.add(w);
      } else {
        results.add(new WeekResult(w, 300, 240, met ? 250 : 100));
      }
      w = w.plusWeeks(1);
    }
    return Rewards.of(List.of(), TYPES, results, breaks, THIS_MONDAY);
  }

  @Test
  void unitsEarnByKindAndAnEasyProblemEarnsOneMore() {
    LocalDate d = THIS_MONDAY.minusDays(3);
    assertThat(units(at("concept", "good", d)).stars()).isEqualTo(1);
    assertThat(units(at("coding", "good", d)).stars()).isEqualTo(1);
    assertThat(units(at("sql", "easy", d)).stars()).isEqualTo(2);
    assertThat(units(at("project", "hard", d)).stars()).isEqualTo(2);
    assertThat(units(at("hld", "good", d)).stars()).isEqualTo(3);
  }

  @Test
  void aUnitNotManagedEarnsNothingUntilItIsAndNeverTwice() {
    LocalDate d = THIS_MONDAY.minusDays(5);
    assertThat(units(at("hld", "again", d)).stars()).isZero();
    assertThat(units(at("hld", "again", d), at("hld", "good", d.plusDays(1))).stars()).isEqualTo(3);
    assertThat(units(at("concept", "good", d), at("concept", "easy", d.plusDays(3))).stars()).isEqualTo(1);
  }

  @Test
  void aDayWithThreeReviewsEarnsAStar() {
    LocalDate learnt = THIS_MONDAY.minusDays(10);
    LocalDate reviewed = THIS_MONDAY.plusDays(1);
    Summary s = units(at("r1", "good", learnt), at("r2", "good", learnt), at("r3", "good", learnt),
        at("r1", "good", reviewed), at("r2", "good", reviewed), at("r3", "hard", reviewed));
    assertThat(s.stars()).isEqualTo(3 + 1);
    assertThat(s.starsThisWeek()).isEqualTo(1);
  }

  @Test
  void aGoalWeekEarnsFiveAndAFullWeekSeven() {
    List<WeekResult> results = List.of(
        new WeekResult(THIS_MONDAY.minusWeeks(2), 300, 240, 240),
        new WeekResult(THIS_MONDAY.minusWeeks(1), 300, 240, 300));
    assertThat(Rewards.of(List.of(), TYPES, results, Set.of(), THIS_MONDAY).stars()).isEqualTo(5 + 7);
  }

  @Test
  void aMissedWeekUsesTheStartingFreezeAndTheNextOneResets() {
    Summary kept = weeks(true, true, false, true);
    assertThat(kept.streak()).isEqualTo(3);
    assertThat(kept.freezes()).isZero();
    assertThat(kept.recentWeeks()).extracting(Rewards.Week::outcome).containsExactly(
        Outcome.GOAL_MET, Outcome.GOAL_MET, Outcome.FREEZE_USED, Outcome.GOAL_MET, Outcome.IN_PROGRESS);

    Summary reset = weeks(true, true, false, false);
    assertThat(reset.streak()).isZero();
    assertThat(reset.longestStreak()).isEqualTo(2);
  }

  @Test
  void aPlannedBreakNeitherCountsNorBreaksTheStreak() {
    Summary s = weeks(true, null, true);
    assertThat(s.streak()).isEqualTo(2);
    assertThat(s.freezes()).isEqualTo(1);
  }

  @Test
  void fourGoalWeeksInARowEarnAFreezeAndNoMoreThanTwoAreBanked() {
    assertThat(weeks(true, true, true, true).freezes()).isEqualTo(2);
    assertThat(weeks(true, true, true, true, true, true, true, true).freezes()).isEqualTo(2);
    // A freeze used breaks the run of four.
    assertThat(weeks(true, true, true, false, true).freezes()).isZero();
  }

  @Test
  void aWeekWithoutAnyPlanAfterTheFirstIsAMissedWeek() {
    List<WeekResult> results = List.of(new WeekResult(THIS_MONDAY.minusWeeks(3), 300, 240, 250));
    Summary s = Rewards.of(List.of(), TYPES, results, Set.of(), THIS_MONDAY);
    assertThat(s.recentWeeks()).extracting(Rewards.Week::outcome)
        .containsExactly(Outcome.GOAL_MET, Outcome.FREEZE_USED, Outcome.MISSED, Outcome.IN_PROGRESS);
    assertThat(s.streak()).isZero();
  }

  @Test
  void thisWeekOnlyEverAdds() {
    List<WeekResult> behind = List.of(new WeekResult(THIS_MONDAY.minusWeeks(1), 300, 240, 250),
        new WeekResult(THIS_MONDAY, 300, 240, 10));
    Summary s = Rewards.of(List.of(), TYPES, behind, Set.of(), THIS_MONDAY);
    assertThat(s.streak()).isEqualTo(1);
    assertThat(s.thisWeekCounts()).isFalse();

    List<WeekResult> met = List.of(behind.get(0), new WeekResult(THIS_MONDAY, 300, 240, 240));
    Summary t = Rewards.of(List.of(), TYPES, met, Set.of(), THIS_MONDAY);
    assertThat(t.streak()).isEqualTo(2);
    assertThat(t.thisWeekCounts()).isTrue();
    assertThat(t.starsThisWeek()).isEqualTo(5);
  }

  @Test
  void milestonesAreReachedQuietly() {
    List<WeekResult> many = new ArrayList<>();
    for (int i = 10; i >= 1; i--) {
      many.add(new WeekResult(THIS_MONDAY.minusWeeks(i), 300, 240, 300));  // 7 stars each
    }
    Summary s = Rewards.of(List.of(), TYPES, many, Set.of(), THIS_MONDAY);
    assertThat(s.stars()).isEqualTo(70);
    assertThat(s.milestonesReached()).containsExactly(50);
    assertThat(s.nextMilestone()).isEqualTo(100);
    assertThat(s.recentWeeks()).hasSize(8);
  }

  @Test
  void aNewLearnerHasNothingAndOneFreeze() {
    Summary s = units();
    assertThat(s.stars()).isZero();
    assertThat(s.streak()).isZero();
    assertThat(s.freezes()).isEqualTo(1);
    assertThat(s.recentWeeks()).extracting(Rewards.Week::outcome).containsExactly(Outcome.IN_PROGRESS);
  }
}
