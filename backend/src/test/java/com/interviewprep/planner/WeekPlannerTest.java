package com.interviewprep.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewprep.planner.WeekPlanner.Candidate;
import com.interviewprep.planner.WeekPlanner.Domain;
import com.interviewprep.planner.WeekPlanner.DueReview;
import com.interviewprep.planner.WeekPlanner.Input;
import com.interviewprep.planner.WeekPlanner.Item;
import com.interviewprep.planner.WeekPlanner.Plan;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The planner on a made-up curriculum large enough to exercise every rule: all twelve domains with
 * their real default weights, a concept-then-problems chain in DSA, and heavy design units.
 */
class WeekPlannerTest {

  private static final LocalDate MONDAY = LocalDate.of(2026, 9, 28);
  private static final List<Integer> EVERY_DAY = List.of(1, 2, 3, 4, 5, 6, 7);

  private static final Map<String, Integer> WEIGHTS = Map.ofEntries(
      Map.entry("dsa", 22), Map.entry("java", 12), Map.entry("spring", 6), Map.entry("databases", 10),
      Map.entry("data", 4), Map.entry("networking", 3), Map.entry("distributed", 11),
      Map.entry("cloud", 4), Map.entry("lld", 10), Map.entry("hld", 12), Map.entry("agentic", 3),
      Map.entry("behavioral", 3));

  private static List<Domain> domains(double strength) {
    return WEIGHTS.entrySet().stream().sorted(Map.Entry.comparingByKey())
        .map(e -> new Domain(e.getKey(), e.getKey().toUpperCase(), e.getValue(), strength)).toList();
  }

  private static Candidate unit(String id, String domain, String type, int minutes, String... prereqs) {
    return new Candidate(id, id, type, domain + ".topic", domain + " topic", domain, 2, minutes,
        List.of(prereqs), 2.5, 0);
  }

  /** Plenty of everything, so the rules, not a shortage of units, shape the week. */
  private static List<Candidate> curriculum() {
    List<Candidate> units = new ArrayList<>();
    units.add(unit("dsa.window.concept", "dsa", "concept", 30));
    for (int i = 1; i <= 6; i++) {
      units.add(unit("dsa.window.p" + i, "dsa", "coding", 25, "dsa.window.concept"));
    }
    for (String d : List.of("java", "spring", "databases", "data", "networking", "distributed",
        "cloud", "agentic")) {
      for (int i = 1; i <= 4; i++) {
        units.add(unit(d + ".u" + i, d, "concept", 30));
      }
    }
    for (int i = 1; i <= 3; i++) {
      units.add(unit("lld.case" + i, "lld", "lld", 90));
      units.add(unit("hld.case" + i, "hld", "hld", 90));
    }
    units.add(unit("beh.ladder", "behavioral", "behavioral", 30));
    units.add(unit("ds.scenario", "distributed", "scenario", 30));
    return units;
  }

  private static Plan plan(double hours, List<Candidate> units, List<DueReview> reviews) {
    return WeekPlanner.plan(new Input(MONDAY, 1, EVERY_DAY, hours, 1.0, domains(2.5), units, Set.of(),
        reviews, Set.of(), List.of()));
  }

  private static Set<String> units(Plan p) {
    return p.items().stream().map(Item::unitId).collect(Collectors.toSet());
  }

  private static Item item(Plan p, String unitId) {
    return p.items().stream().filter(i -> i.unitId().equals(unitId)).findFirst().orElseThrow();
  }

  @Test
  void theWeekIsNinetyPercentOfTheHoursAndTheGoalEightyPercentOfThat() {
    Plan p = plan(9, curriculum(), List.of());
    assertThat(p.plannedMinutes()).isEqualTo(486);
    assertThat(p.goalMinutes()).isEqualTo(389);
    int used = p.items().stream().mapToInt(Item::minutes).sum();
    assertThat(used).isBetween(486 - 30, 486);
  }

  @Test
  void everyRequiredGroupAppearsOnce() {
    Plan p = plan(9, curriculum(), List.of());
    assertThat(p.items()).anyMatch(i -> i.unitId().startsWith("dsa."));
    assertThat(p.items()).anyMatch(i -> i.unitId().startsWith("java.") || i.unitId().startsWith("spring."));
    assertThat(p.items()).anyMatch(i -> i.unitId().startsWith("lld."));
    assertThat(p.items()).anyMatch(i -> i.unitId().startsWith("hld."));
    assertThat(p.items()).anyMatch(i -> i.unitId().equals("beh.ladder") || i.unitId().equals("ds.scenario"));
    assertThat(p.items()).filteredOn(i -> i.reason().startsWith("Required every week")).hasSize(6);
    assertThat(p.notes()).isEmpty();
  }

  @Test
  void atMostTwoHeavyUnitsNeverOnTheSameDayAndTowardsTheWeekend() {
    Plan p = plan(20, curriculum(), List.of());
    List<Item> heavy = p.items().stream().filter(i -> i.minutes() >= 75).toList();
    assertThat(heavy).hasSize(2);
    assertThat(heavy.get(0).day()).isNotEqualTo(heavy.get(1).day());
    assertThat(heavy).allMatch(i -> i.day() >= 6);
  }

  @Test
  void dsaIsSpreadOverAtLeastThreeDays() {
    Plan p = plan(9, curriculum(), List.of());
    List<Item> dsa = p.items().stream().filter(i -> i.unitId().startsWith("dsa.")).toList();
    assertThat(dsa.size()).isGreaterThanOrEqualTo(3);
    assertThat(dsa.stream().map(Item::day).distinct().count()).isGreaterThanOrEqualTo(3);
  }

  @Test
  void aProblemComesWithItsConceptAndNeverBeforeIt() {
    Plan p = plan(9, curriculum(), List.of());
    Item concept = item(p, "dsa.window.concept");
    p.items().stream().filter(i -> i.unitId().startsWith("dsa.window.p"))
        .forEach(problem -> assertThat(problem.day()).isGreaterThanOrEqualTo(concept.day()));
  }

  @Test
  void aProblemWhoseConceptDidNotFitWaitsForNextWeek() {
    // Only one hour: the concept fits, but not every problem after it.
    Plan p = plan(1, List.of(unit("dsa.window.p1", "dsa", "coding", 25, "dsa.window.concept"),
        unit("dsa.window.concept", "dsa", "concept", 50)), List.of());
    assertThat(units(p)).containsExactly("dsa.window.concept");
  }

  @Test
  void aDonePrerequisiteUnlocksItsProblems() {
    Plan p = WeekPlanner.plan(new Input(MONDAY, 1, EVERY_DAY, 2, 1.0, domains(2.5),
        List.of(unit("dsa.window.p1", "dsa", "coding", 25, "dsa.window.concept")),
        Set.of("dsa.window.concept"), List.of(), Set.of(), List.of()));
    assertThat(units(p)).containsExactly("dsa.window.p1");
  }

  @Test
  void reviewsComeFirstUpToAFifthOfTheWeekOldestFirstAndNotBeforeTheyAreDue() {
    List<DueReview> due = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      due.add(new DueReview("done.r" + i, "concept", 30, MONDAY.plusDays(i % 7).minusDays(3)));
    }
    Plan p = plan(9, curriculum(), due);
    List<Item> reviews = p.items().stream().filter(i -> i.kind().equals("review")).toList();
    assertThat(reviews.stream().mapToInt(Item::minutes).sum()).isLessThanOrEqualTo((int) (486 * 0.2));
    assertThat(reviews).hasSize(6);
    assertThat(p.notes()).anyMatch(n -> n.startsWith("6 due reviews did not fit"));
    // The six overdue ones (Fri, Sat, Sun before this week) fill the allowance; those due this week wait.
    assertThat(reviews).extracting(Item::unitId)
        .containsExactlyInAnyOrder("done.r0", "done.r7", "done.r1", "done.r8", "done.r2", "done.r9");
    assertThat(reviews).allMatch(r -> r.reason().startsWith("Review overdue since"));
  }

  @Test
  void aReviewIsNeverPlacedBeforeItsDueDay() {
    Plan p = plan(9, curriculum(), List.of(new DueReview("done.x", "coding", 30, MONDAY.plusDays(4))));
    assertThat(item(p, "done.x").day()).isGreaterThanOrEqualTo(5);
    assertThat(item(p, "done.x").minutes()).isEqualTo(15);
  }

  @Test
  void aWeakerDomainGetsMoreOfTheWeekThanAStrongerOneOfTheSameWeight() {
    List<Domain> ds = new ArrayList<>(domains(2.5));
    ds.replaceAll(d -> d.id().equals("lld") ? new Domain("lld", "LLD", 10, 1.0)
        : d.id().equals("databases") ? new Domain("databases", "DB", 10, 4.5) : d);
    Plan p = WeekPlanner.plan(new Input(MONDAY, 1, EVERY_DAY, 9, 1.0, ds, curriculum(), Set.of(), List.of(), Set.of(), List.of()));
    int lld = share(p, "lld");
    int db = share(p, "databases");
    assertThat(lld).isGreaterThan(db);
    // Bounded: weakness moves a share by at most the 0.7–1.5 factor.
    assertThat((double) lld / db).isLessThanOrEqualTo(1.5 / 0.7 + 0.1);
  }

  @Test
  void aWeightOfZeroRemovesADomainEvenFromTheRequiredGroups() {
    List<Domain> ds = new ArrayList<>(domains(2.5));
    ds.replaceAll(d -> d.id().equals("hld") ? new Domain("hld", "HLD", 0, 2.5) : d);
    Plan p = WeekPlanner.plan(new Input(MONDAY, 1, EVERY_DAY, 9, 1.0, ds, curriculum(), Set.of(), List.of(), Set.of(), List.of()));
    assertThat(p.items()).noneMatch(i -> i.unitId().startsWith("hld."));
    assertThat(p.shares()).noneMatch(s -> s.domainId().equals("hld"));
  }

  @Test
  void aReleaseAddingManyUnitsToOneDomainDoesNotTakeOverTheWeek() {
    Plan before = plan(9, curriculum(), List.of());
    List<Candidate> grown = new ArrayList<>(curriculum());
    for (int i = 0; i < 40; i++) {
      grown.add(unit("agentic.new" + i, "agentic", "concept", 20));
    }
    Plan after = plan(9, grown, List.of());
    assertThat(after.shares()).isEqualTo(before.shares());
    int agenticMinutes = after.items().stream().filter(i -> i.unitId().startsWith("agentic."))
        .mapToInt(Item::minutes).sum();
    assertThat(agenticMinutes).isLessThanOrEqualTo(60);
  }

  @Test
  void whatIsMissingIsSaidNotHidden() {
    Plan p = plan(9, List.of(unit("dsa.window.concept", "dsa", "concept", 30)), List.of());
    assertThat(p.notes()).contains("No system design units to learn yet, so this week has none.");
    assertThat(p.notes()).anyMatch(n -> n.endsWith("minutes are unplanned: nothing else to learn is ready yet."));
  }

  @Test
  void everyItemSaysWhy() {
    Plan p = plan(9, curriculum(), List.of());
    assertThat(p.items()).allMatch(i -> i.reason().contains("% of this week") || i.kind().equals("review"));
    assertThat(item(p, "dsa.window.concept").reason())
        .startsWith("Required every week: DSA; DSA is ").contains("you are at 2.5/5 in dsa topic");
  }

  @Test
  void theSameInputGivesTheSamePlan() {
    assertThat(plan(9, curriculum(), List.of())).isEqualTo(plan(9, curriculum(), List.of()));
  }

  @Test
  void onlyTheStudyDaysAreUsed() {
    Plan p = WeekPlanner.plan(new Input(MONDAY, 1, List.of(2, 4, 6), 6, 1.0, domains(2.5), curriculum(),
        Set.of(), List.of(), Set.of(), List.of()));
    assertThat(p.items()).allMatch(i -> Set.of(2, 4, 6).contains(i.day()));
  }

  @Test
  void aWeekFirstOpenedMidweekUsesOnlyTheDaysLeftAndShrinksToMatch() {
    Plan p = WeekPlanner.plan(new Input(MONDAY, 3, EVERY_DAY, 9, 1.0, domains(2.5), curriculum(),
        Set.of(), List.of(new DueReview("done.x", "concept", 30, MONDAY.minusDays(2))), Set.of(), List.of()));
    assertThat(p.items()).allMatch(i -> i.day() >= 3);
    assertThat(p.plannedMinutes()).isEqualTo((int) Math.round(486 * 5 / 7.0));
    assertThat(p.notes()).contains("Made mid-week, so it covers the 5 study days left.");
  }

  @Test
  void aWeekWithNoStudyDaysLeftIsEmptyAndSaysSo() {
    Plan p = WeekPlanner.plan(new Input(MONDAY, 7, List.of(1, 2, 3), 9, 1.0, domains(2.5), curriculum(),
        Set.of(), List.of(), Set.of(), List.of()));
    assertThat(p.items()).isEmpty();
    assertThat(p.notes()).containsExactly("No study days are left this week; the next plan starts on Monday.");
  }

  @Test
  void aUnitPlacedNowComesFirstWhateverItsArea() {
    Plan p = WeekPlanner.plan(new Input(MONDAY, 1, EVERY_DAY, 9, 1.0, domains(2.5), curriculum(),
        Set.of(), List.of(), Set.of("agentic.u4"), List.of()));
    assertThat(item(p, "agentic.u4").reason()).startsWith("You chose to start it now");
  }

  @Test
  void lastWeeksUnfinishedItemsComeFirstUpToTheirShare() {
    // Three items left over; together they need more than 30% of a 3-hour week (162 minutes: 48 allowed).
    List<String> leftOver = List.of("java.u1", "spring.u1", "databases.u1");
    Plan p = WeekPlanner.plan(new Input(MONDAY, 1, EVERY_DAY, 3, 1.0, domains(2.5), curriculum(),
        Set.of(), List.of(), Set.of(), leftOver));
    assertThat(item(p, "java.u1").reason()).startsWith("Carried over from last week");
    long carried = p.items().stream().filter(i -> i.reason().startsWith("Carried over")).count();
    assertThat(carried).isEqualTo(1);  // 30 + 30 > 48, so only the first fits
    assertThat(p.notes()).anyMatch(n -> n.startsWith("2 unfinished items from last week did not fit"));
  }

  @Test
  void aCarriedItemThatIsNoLongerACandidateIsSkippedQuietly() {
    Plan p = WeekPlanner.plan(new Input(MONDAY, 1, EVERY_DAY, 9, 1.0, domains(2.5), curriculum(),
        Set.of(), List.of(), Set.of(), List.of("done.elsewhere", "java.u2")));
    assertThat(item(p, "java.u2").reason()).startsWith("Carried over from last week");
    assertThat(p.notes()).noneMatch(n -> n.contains("unfinished"));
  }

  @Test
  void aCarriedProblemStillWaitsForItsConcept() {
    Plan p = WeekPlanner.plan(new Input(MONDAY, 1, EVERY_DAY, 9, 1.0, domains(2.5),
        List.of(unit("dsa.window.p1", "dsa", "coding", 25, "dsa.window.concept"),
            unit("dsa.window.concept", "dsa", "concept", 30)),
        Set.of(), List.of(), Set.of(), List.of("dsa.window.p1")));
    // Not ready when the carry-over runs, so it is not carried; it still lands after its concept.
    assertThat(item(p, "dsa.window.p1").reason()).doesNotStartWith("Carried over");
    assertThat(item(p, "dsa.window.p1").day()).isGreaterThanOrEqualTo(item(p, "dsa.window.concept").day());
  }

  private static int share(Plan p, String domain) {
    return p.shares().stream().filter(s -> s.domainId().equals(domain)).findFirst().orElseThrow().percent();
  }
}
