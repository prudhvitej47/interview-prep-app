package com.interviewprep.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewprep.planner.WeekPlanner.Candidate;
import com.interviewprep.planner.WeekPlanner.Domain;
import com.interviewprep.planner.WeekPlanner.DueReview;
import com.interviewprep.planner.WeekPlanner.Input;
import com.interviewprep.planner.WeekPlanner.Item;
import com.interviewprep.planner.WeekPlanner.Plan;
import com.interviewprep.planner.WeekPlanner.ProjectQuestion;
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
        reviews, Set.of()));
  }

  private static Set<String> units(Plan p) {
    return p.items().stream().map(Item::unitId).collect(Collectors.toSet());
  }

  private static Item item(Plan p, String unitId) {
    return p.items().stream().filter(i -> i.unitId().equals(unitId)).findFirst().orElseThrow();
  }

  @Test
  void theSharePreviewSaysWhatTheWeightsAloneWouldGiveAndHowStrongTheLearnerIs() {
    // Equal weights; the learner is new to one area and fluent in the other.
    List<Domain> domains = List.of(new Domain("aa", "Area A", 50, 0), new Domain("bb", "Area B", 50, 5),
        new Domain("cc", "Area C", 0, 1), new Domain("dd", "Area D", 10, 1));
    Set<String> withUnits = Set.of("aa", "bb", "cc");
    List<WeekPlanner.ShareDetail> details = WeekPlanner.shareDetails(domains, withUnits);
    // 50 × 1.5 = 75 against 50 × 0.7 = 35; a weight of 0 or nothing to learn gets no share at all.
    assertThat(details).containsExactly(new WeekPlanner.ShareDetail("aa", "Area A", 68, 50, 0),
        new WeekPlanner.ShareDetail("bb", "Area B", 32, 50, 5));
    // The same numbers the plan itself uses.
    Map<String, Double> share = WeekPlanner.shareOf(domains, withUnits);
    assertThat(details).allMatch(d -> d.percent() == (int) Math.round(share.get(d.domainId()) * 100));
  }

  @Test
  void theWeekIsNinetyPercentOfTheHoursAndTheGoalEightyPercentOfThat() {
    Plan p = plan(9, curriculum(), List.of());
    assertThat(p.plannedMinutes()).isEqualTo(486);
    assertThat(p.goalMinutes()).isEqualTo(389);
    int used = p.items().stream().mapToInt(Item::minutes).sum();
    assertThat(used).isBetween(486 - 30, 486);
  }

  private static int learnMinutes(Plan p, String domainPrefix) {
    return p.items().stream().filter(i -> i.kind().equals("learn"))
        .filter(i -> domainPrefix == null || i.unitId().startsWith(domainPrefix))
        .mapToInt(Item::minutes).sum();
  }

  @Test
  void aDomainGetsTheShareItsWeightPromises() {
    Plan p = plan(9, curriculum(), List.of());
    // The biggest DSA unit is 30 minutes, so the plan can be at most one unit away from the share.
    double target = share(p, "dsa") / 100.0 * learnMinutes(p, null);
    assertThat((double) learnMinutes(p, "dsa.")).isBetween(target - 30, target + 30);
    assertThat(p.items()).noneMatch(i -> i.reason().startsWith("Required every week"));
  }

  @Test
  void aHeavilyWeightedDomainIsNotSqueezedOutByTheSmallOnes() {
    // A learner who put half her week on DSA, with a small week: six hours.
    Map<String, Integer> weights = Map.ofEntries(
        Map.entry("dsa", 50), Map.entry("java", 5), Map.entry("spring", 5), Map.entry("databases", 2),
        Map.entry("data", 2), Map.entry("networking", 2), Map.entry("distributed", 5), Map.entry("cloud", 5),
        Map.entry("lld", 5), Map.entry("hld", 10), Map.entry("agentic", 5), Map.entry("behavioral", 4));
    List<Domain> ds = weights.entrySet().stream().sorted(Map.Entry.comparingByKey())
        .map(e -> new Domain(e.getKey(), e.getKey().toUpperCase(), e.getValue(), 2.5)).toList();
    Plan p = WeekPlanner.plan(new Input(MONDAY, 1, EVERY_DAY, 6, 1.0, ds, curriculum(), Set.of(), List.of(),
        Set.of()));
    int learning = learnMinutes(p, null);
    assertThat(share(p, "dsa")).isEqualTo(50);
    assertThat(learnMinutes(p, "dsa.")).isGreaterThanOrEqualTo((int) (0.4 * learning));
    assertThat(p.items().stream().filter(i -> i.unitId() != null && i.unitId().startsWith("dsa.")).count())
        .isGreaterThanOrEqualTo(5);
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
        Set.of("dsa.window.concept"), List.of(), Set.of()));
    assertThat(units(p)).containsExactly("dsa.window.p1");
  }

  @Test
  void reviewsComeFirstUpToATenthOfTheWeekOldestFirstAndNotBeforeTheyAreDue() {
    List<DueReview> due = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      due.add(new DueReview("done.r" + i, "concept", 30, MONDAY.plusDays(i % 7).minusDays(3)));
    }
    Plan p = plan(9, curriculum(), due);
    List<Item> reviews = p.items().stream().filter(i -> i.kind().equals("review")).toList();
    assertThat(reviews.stream().mapToInt(Item::minutes).sum()).isLessThanOrEqualTo((int) (486 * 0.1));
    // Each 30-minute concept is reviewed in 15, so three fit in 48 minutes.
    assertThat(reviews).hasSize(3);
    assertThat(p.notes()).anyMatch(n -> n.startsWith("9 due reviews did not fit"));
    // The longest overdue come first (Friday's two, then Saturday's first); the rest wait in the queue.
    assertThat(reviews).extracting(Item::unitId).containsExactlyInAnyOrder("done.r0", "done.r7", "done.r1");
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
    Plan p = WeekPlanner.plan(new Input(MONDAY, 1, EVERY_DAY, 9, 1.0, ds, curriculum(), Set.of(), List.of(), Set.of()));
    int lld = share(p, "lld");
    int db = share(p, "databases");
    assertThat(lld).isGreaterThan(db);
    // Bounded: weakness moves a share by at most the 0.7–1.5 factor.
    assertThat((double) lld / db).isLessThanOrEqualTo(1.5 / 0.7 + 0.1);
  }

  @Test
  void aWeightOfZeroRemovesADomain() {
    List<Domain> ds = new ArrayList<>(domains(2.5));
    ds.replaceAll(d -> d.id().equals("hld") ? new Domain("hld", "HLD", 0, 2.5) : d);
    Plan p = WeekPlanner.plan(new Input(MONDAY, 1, EVERY_DAY, 9, 1.0, ds, curriculum(), Set.of(), List.of(), Set.of()));
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
  void unplannedTimeIsSaidNotHidden() {
    Plan p = plan(9, List.of(unit("dsa.window.concept", "dsa", "concept", 30)), List.of());
    assertThat(p.notes()).anyMatch(n -> n.endsWith("minutes are unplanned: nothing else to learn is ready yet."));
  }

  @Test
  void everyItemSaysWhy() {
    Plan p = plan(9, curriculum(), List.of());
    assertThat(p.items()).allMatch(i -> i.reason().contains("% of this week") || i.kind().equals("review"));
    assertThat(item(p, "dsa.window.concept").reason())
        .startsWith("DSA is ").contains("you are at 2.5/5 in dsa topic");
  }

  @Test
  void theSameInputGivesTheSamePlan() {
    assertThat(plan(9, curriculum(), List.of())).isEqualTo(plan(9, curriculum(), List.of()));
  }

  @Test
  void onlyTheStudyDaysAreUsed() {
    Plan p = WeekPlanner.plan(new Input(MONDAY, 1, List.of(2, 4, 6), 6, 1.0, domains(2.5), curriculum(),
        Set.of(), List.of(), Set.of()));
    assertThat(p.items()).allMatch(i -> Set.of(2, 4, 6).contains(i.day()));
  }

  @Test
  void aWeekFirstOpenedMidweekUsesOnlyTheDaysLeftAndShrinksToMatch() {
    Plan p = WeekPlanner.plan(new Input(MONDAY, 3, EVERY_DAY, 9, 1.0, domains(2.5), curriculum(),
        Set.of(), List.of(new DueReview("done.x", "concept", 30, MONDAY.minusDays(2))), Set.of()));
    assertThat(p.items()).allMatch(i -> i.day() >= 3);
    assertThat(p.plannedMinutes()).isEqualTo((int) Math.round(486 * 5 / 7.0));
    assertThat(p.notes()).contains("Made mid-week, so it covers the 5 study days left.");
  }

  @Test
  void aWeekWithNoStudyDaysLeftIsEmptyAndSaysSo() {
    Plan p = WeekPlanner.plan(new Input(MONDAY, 7, List.of(1, 2, 3), 9, 1.0, domains(2.5), curriculum(),
        Set.of(), List.of(), Set.of()));
    assertThat(p.items()).isEmpty();
    assertThat(p.notes()).containsExactly("No study days are left this week; the next plan starts on Monday.");
  }

  @Test
  void aUnitPlacedNowComesFirstWhateverItsArea() {
    Plan p = WeekPlanner.plan(new Input(MONDAY, 1, EVERY_DAY, 9, 1.0, domains(2.5), curriculum(),
        Set.of(), List.of(), Set.of("agentic.u4")));
    assertThat(item(p, "agentic.u4").reason()).startsWith("You chose to start it now");
  }

  // ---------------------------------------------------------------------------------------------
  // Questions about the learner's own projects. The projects are invented; this repository is public.
  // ---------------------------------------------------------------------------------------------

  private static final List<String> LADDER = List.of("walkthrough", "why", "scale", "failure", "change", "story");

  /** A project's ladder of 15-minute questions, ids {@code base+0…}; none rated yet. */
  private static List<ProjectQuestion> ladder(long base, long project, String name, int projectOrder, int count) {
    List<ProjectQuestion> out = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      out.add(new ProjectQuestion(base + i, project, name, projectOrder, i, LADDER.get(i % 6), 15, null));
    }
    return out;
  }

  private static ProjectQuestion rated(ProjectQuestion q, LocalDate dueOn) {
    return new ProjectQuestion(q.questionId(), q.projectId(), q.projectName(), q.projectOrder(),
        q.questionOrder(), q.rung(), q.minutes(), dueOn);
  }

  private static List<ProjectQuestion> twoProjects() {
    List<ProjectQuestion> qs = new ArrayList<>(ladder(100, 1, "Example ledger", 0, 6));
    qs.addAll(ladder(200, 2, "Example gateway", 1, 3));
    return qs;
  }

  private static Plan withProjects(double hours, int fromDay, List<Integer> days, List<ProjectQuestion> qs,
      List<Long> carried) {
    return WeekPlanner.plan(new Input(MONDAY, fromDay, days, hours, 1.0, domains(2.5), curriculum(), Set.of(),
        List.of(), Set.of(), qs, carried));
  }

  private static List<Item> projectItems(Plan p) {
    return p.items().stream().filter(i -> i.kind().equals("project")).toList();
  }

  private static List<Long> questionIds(Plan p) {
    return projectItems(p).stream().map(Item::projectQuestionId).toList();
  }

  @Test
  void aLearnerWithNoProjectsGetsExactlyThePlanTheyGotBefore() {
    Plan before = plan(9, curriculum(), List.of(new DueReview("done.x", "concept", 30, MONDAY)));
    Plan now = WeekPlanner.plan(new Input(MONDAY, 1, EVERY_DAY, 9, 1.0, domains(2.5), curriculum(), Set.of(),
        List.of(new DueReview("done.x", "concept", 30, MONDAY)), Set.of(), List.of(), List.of()));
    assertThat(now).isEqualTo(before);
    assertThat(projectItems(now)).isEmpty();
    assertThat(now.notes()).noneMatch(n -> n.contains("project question") || n.contains("your projects"));
  }

  @Test
  void projectQuestionsTakeAtMostATenthOfTheWeekOffTheTop() {
    Plan without = plan(9, curriculum(), List.of());
    Plan p = withProjects(9, 1, EVERY_DAY, twoProjects(), List.of());
    // 10% of 486 is 48 minutes: three 15-minute questions.
    assertThat(projectItems(p)).hasSize(3);
    assertThat(projectItems(p).stream().mapToInt(Item::minutes).sum()).isLessThanOrEqualTo(48);
    assertThat(projectItems(p)).allMatch(i -> i.unitId() == null && i.projectQuestionId() != null);
    // Off the top: the domain shares are untouched, and learning shrinks by what the questions take.
    assertThat(p.shares()).isEqualTo(without.shares());
    int learning = p.items().stream().filter(i -> i.kind().equals("learn")).mapToInt(Item::minutes).sum();
    assertThat(learning).isLessThanOrEqualTo(486 - 45);
    assertThat(p.notes()).contains("Questions about your projects take 45 minutes, off the top like reviews"
        + " (up to 10% of the week, never two on one day).");
  }

  @Test
  void aSmallWeekStillGetsOneQuestionWhenTheShareAllowsIt() {
    // 3 hours: 162 planned, 16 minutes for projects, so one 15-minute question.
    assertThat(projectItems(withProjects(3, 1, EVERY_DAY, twoProjects(), List.of()))).hasSize(1);
    // 2 hours: 108 planned, 10 minutes, and no question is that short.
    Plan tiny = withProjects(2, 1, EVERY_DAY, twoProjects(), List.of());
    assertThat(projectItems(tiny)).isEmpty();
    assertThat(tiny.notes()).contains("No project question fitted this week: 10% of it is 10 minutes.");
  }

  @Test
  void dueQuestionsComeFirstEarliestDueFirstAndNotBeforeTheirDay() {
    List<ProjectQuestion> qs = new ArrayList<>(twoProjects());
    qs.set(1, rated(qs.get(1), MONDAY.plusDays(3)));   // due Thursday
    qs.set(7, rated(qs.get(7), MONDAY.minusDays(2)));  // overdue since Saturday
    qs.set(2, rated(qs.get(2), MONDAY.plusDays(10)));  // rated, due next week: not this one
    Plan p = withProjects(9, 1, EVERY_DAY, qs, List.of());
    // Overdue, then due, then the first new rung of the project already under way (the ledger).
    assertThat(questionIds(p)).containsExactlyInAnyOrder(201L, 101L, 100L);
    Item overdue = projectItems(p).stream().filter(i -> i.projectQuestionId() == 201L).findFirst().orElseThrow();
    Item due = projectItems(p).stream().filter(i -> i.projectQuestionId() == 101L).findFirst().orElseThrow();
    assertThat(overdue.reason()).startsWith("Rehearse again, overdue since Sat 26 Sept")
        .contains("why it was built this way on Example gateway");
    assertThat(due.reason()).startsWith("Rehearse again, due Thu 1 Oct");
    assertThat(due.day()).isGreaterThanOrEqualTo(4);
    assertThat(questionIds(p)).doesNotContain(102L);
  }

  @Test
  void newQuestionsFollowOneProjectsLadderInOrder() {
    Plan p = withProjects(9, 1, EVERY_DAY, twoProjects(), List.of());
    assertThat(questionIds(p)).containsExactlyInAnyOrder(100L, 101L, 102L);
    assertThat(projectItems(p)).allMatch(i -> i.reason().startsWith("Next on the Example ledger ladder: "));
    assertThat(projectItems(p).stream().filter(i -> i.projectQuestionId() == 100L).findFirst().orElseThrow()
        .reason()).startsWith("Next on the Example ledger ladder: the walk-through. Your projects take up to 10%");
  }

  @Test
  void aProjectAlreadyUnderWayIsFinishedBeforeAnotherIsStarted() {
    List<ProjectQuestion> qs = new ArrayList<>(twoProjects());
    // The gateway (second on the page) has one rung rated and not due yet; the ledger is untouched.
    qs.set(6, rated(qs.get(6), MONDAY.plusDays(20)));
    Plan p = withProjects(9, 1, EVERY_DAY, qs, List.of());
    // Its two remaining rungs, in order, then the ledger's first.
    assertThat(questionIds(p)).containsExactlyInAnyOrder(201L, 202L, 100L);
  }

  @Test
  void aRungThatDoesNotFitEndsTheWalkRatherThanBeingSkipped() {
    List<ProjectQuestion> qs = new ArrayList<>(ladder(100, 1, "Example ledger", 0, 3));
    ProjectQuestion longOne = qs.get(1);
    qs.set(1, new ProjectQuestion(longOne.questionId(), 1, "Example ledger", 0, 1, "why", 45, null));
    Plan p = withProjects(9, 1, EVERY_DAY, qs, List.of());
    assertThat(questionIds(p)).containsExactly(100L);
  }

  @Test
  void neverTwoProjectQuestionsOnOneDayOnTheLeastBusyDays() {
    // 20 hours leaves 108 minutes for projects, room for seven, but only three study days.
    Plan p = withProjects(20, 1, List.of(2, 4, 6), twoProjects(), List.of());
    assertThat(projectItems(p)).hasSize(3);
    assertThat(projectItems(p).stream().map(Item::day).distinct()).containsExactlyInAnyOrder(2, 4, 6);
  }

  @Test
  void aWeekMadeMidweekKeepsProjectQuestionsToTheDaysLeft() {
    Plan p = withProjects(9, 5, EVERY_DAY, twoProjects(), List.of());
    // 3 days left: 208 planned, 20 minutes for projects.
    assertThat(p.plannedMinutes()).isEqualTo((int) Math.round(486 * 3 / 7.0));
    assertThat(projectItems(p)).hasSize(1);
    assertThat(projectItems(p)).allMatch(i -> i.day() >= 5);
  }

  @Test
  void lastWeeksUnratedQuestionsComeFirstAndAGoneOneIsSkipped() {
    List<ProjectQuestion> qs = new ArrayList<>(twoProjects());
    qs.set(1, rated(qs.get(1), MONDAY.minusDays(1)));  // overdue
    Plan p = withProjects(9, 1, EVERY_DAY, qs, List.of(999L, 202L));
    assertThat(questionIds(p)).containsExactlyInAnyOrder(202L, 101L, 100L);
    Item carried = projectItems(p).stream().filter(i -> i.projectQuestionId() == 202L).findFirst().orElseThrow();
    assertThat(carried.reason()).isEqualTo("Carried over from last week: what happens at scale on Example gateway.");
  }

  @Test
  void carriedQuestionsAreBoundedByTheProjectShareAndTheRestAreCounted() {
    // 3 hours: 162 planned, 16 minutes for projects: one of three carried 15-minute questions fits.
    List<ProjectQuestion> qs = ladder(100, 1, "Example ledger", 0, 3);
    Plan p = withProjects(3, 1, EVERY_DAY, qs, List.of(100L, 101L, 102L));
    assertThat(questionIds(p)).containsExactly(100L);
    assertThat(p.notes()).anyMatch(n -> n.startsWith("2 unrated project questions from last week did not fit"));
  }

  @Test
  void theSameInputWithProjectsGivesTheSamePlan() {
    assertThat(withProjects(9, 1, EVERY_DAY, twoProjects(), List.of()))
        .isEqualTo(withProjects(9, 1, EVERY_DAY, twoProjects(), List.of()));
  }

  private static int share(Plan p, String domain) {
    return p.shares().stream().filter(s -> s.domainId().equals(domain)).findFirst().orElseThrow().percent();
  }

  private static Plan rebuilt(List<Integer> studyDays, int keptToday) {
    return WeekPlanner.plan(new Input(MONDAY, 1, studyDays, 9, 1.0, domains(2.5), curriculum(), Set.of(),
        List.of(), Set.of(), List.of(), List.of(), keptToday));
  }

  @Test
  void workKeptFromTodayTakesUpToTodaysShareOfARebuiltWeek() {
    // 486 minutes over seven days is 69 a day.
    assertThat(rebuilt(EVERY_DAY, 60).plannedMinutes()).isEqualTo(426);
    assertThat(rebuilt(EVERY_DAY, 200).plannedMinutes()).isEqualTo(486 - 69);
    // Today is not a study day: the days left were not counting on it.
    assertThat(rebuilt(List.of(2, 3, 4, 5, 6, 7), 60).plannedMinutes()).isEqualTo(486);
    assertThat(rebuilt(EVERY_DAY, 0)).isEqualTo(plan(9, curriculum(), List.of()));
  }

  private static final List<Domain> TWO = List.of(new Domain("aa", "Area A", 60, 2.5),
      new Domain("bb", "Area B", 40, 2.5));

  private static List<Candidate> twoAreas() {
    List<Candidate> units = new ArrayList<>();
    for (int i = 1; i <= 4; i++) {
      units.add(unit("aa.u" + i, "aa", "concept", 30));
      units.add(unit("bb.u" + i, "bb", "concept", 30));
    }
    return units;
  }

  private static List<Item> extra(int minutes, List<Candidate> units, Set<String> done, Set<String> inPlan,
      Map<String, Integer> assigned, int heavyInPlan) {
    return WeekPlanner.extra(new WeekPlanner.Extra(5, minutes, TWO, units, done, inPlan, assigned, heavyInPlan));
  }

  @Test
  void extrasPullTheWeekTowardsTheWeights() {
    // The plan gave B 120 minutes and A none: 60 more puts A's target at 108, so both go to A.
    List<Item> items = extra(60, twoAreas(), Set.of(), Set.of(), Map.of("bb", 120), 0);
    assertThat(items).extracting(Item::unitId).containsExactly("aa.u1", "aa.u2");
    assertThat(items).allMatch(i -> i.day() == 5 && i.kind().equals("learn")
        && i.reason().startsWith(WeekPlanner.EXTRA + "; Area A is 60% of this week"));
    // With A already ahead, the extras go to B.
    assertThat(extra(60, twoAreas(), Set.of(), Set.of(), Map.of("aa", 180), 0))
        .extracting(Item::unitId).containsExactly("bb.u1", "bb.u2");
  }

  @Test
  void extrasStayWithinTheirMinutes() {
    assertThat(extra(90, twoAreas(), Set.of(), Set.of(), Map.of(), 0)).extracting(Item::minutes)
        .containsExactly(30, 30, 30);
    List<Candidate> long45 = List.of(unit("aa.long", "aa", "concept", 45), unit("bb.long", "bb", "concept", 45));
    assertThat(extra(30, long45, Set.of(), Set.of(), Map.of(), 0)).isEmpty();
  }

  @Test
  void extrasSkipWhatIsDoneInThePlanNotReadyOrOverTheHeavyLimit() {
    List<Candidate> units = List.of(unit("aa.done", "aa", "concept", 30), unit("aa.planned", "aa", "concept", 30),
        unit("aa.next", "aa", "coding", 30, "bb.case"), unit("aa.after", "aa", "coding", 30, "aa.planned"),
        unit("bb.case", "bb", "hld", 90), unit("bb.case2", "bb", "hld", 90));
    // aa.next waits on a heavy unit the limit keeps out; aa.after's prerequisite is in the plan, so it is ready.
    assertThat(extra(90, units, Set.of("aa.done"), Set.of("aa.planned"), Map.of(), 2))
        .extracting(Item::unitId).containsExactly("aa.after");
    // One heavy unit at most, as they all share a day.
    assertThat(extra(90, units.subList(4, 6), Set.of(), Set.of(), Map.of(), 0))
        .extracting(Item::unitId).containsExactly("bb.case");
  }
}
