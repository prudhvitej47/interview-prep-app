package com.interviewprep.planner;

import static com.interviewprep.progress.ProgressQueries.STUDY_ZONE;

import com.interviewprep.curriculum.CurriculumQueries;
import com.interviewprep.curriculum.CurriculumQueries.PlannableUnit;
import com.interviewprep.curriculum.CurriculumQueries.TopicPlace;
import com.interviewprep.curriculum.CurriculumQueries.UnitSummary;
import com.interviewprep.curriculum.DomainCatalog;
import com.interviewprep.evidence.EvidenceQueries;
import com.interviewprep.learner.LearnerPrincipal;
import com.interviewprep.learner.LearnerProfile;
import com.interviewprep.learner.LearnerProfile.NotForMe;
import com.interviewprep.learner.LearnerProfile.Ratings;
import com.interviewprep.learner.LearnerProfile.WeekSettings;
import com.interviewprep.planner.WeekPlanner.Candidate;
import com.interviewprep.planner.WeekPlanner.DueReview;
import com.interviewprep.planner.WeekPlanner.Item;
import com.interviewprep.planner.WeekPlanner.Share;
import com.interviewprep.progress.ProgressQueries;
import com.interviewprep.progress.ProgressQueries.Attempt;
import com.interviewprep.projects.ProjectPlanning;
import com.interviewprep.projects.ProjectPlanning.QuestionRef;
import com.interviewprep.projects.ProjectPlanning.Rated;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * This week's plan. It is made the first time the week is opened — there is no Monday job to miss
 * while the VM restarts — and then stays as it is: a curriculum release mid-week changes next week,
 * not this one (proposal F10). "Rebuild", for after changing hours or weights, redraws what is not
 * done yet and keeps what is: the week's done items stay in it, with their minutes. A learner who
 * finishes the whole plan early can add more learning to it ({@code POST /api/plan/extra}).
 */
@RestController
class PlanController {

  // A plan is sized from the last two: two weeks under 60% done shrink it 15%, two at 100% grow
  // it 10%, never past the learner's stated hours (proposal F7).
  private static final double SHRINK = 0.85;
  private static final double GROW = 1.10;
  private static final int MAX_TOPICS_TO_ASK = 5;

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final JsonMapper json;
  private final CurriculumQueries curriculum;
  private final DomainCatalog domains;
  private final LearnerProfile profile;
  private final ProgressQueries progress;
  private final EvidenceQueries evidence;
  private final PlanQueries plans;
  private final PlacementService placements;
  private final ProjectPlanning projects;

  PlanController(NamedParameterJdbcTemplate jdbc, TransactionTemplate transaction, JsonMapper json,
      CurriculumQueries curriculum, DomainCatalog domains, LearnerProfile profile,
      ProgressQueries progress, EvidenceQueries evidence, PlanQueries plans,
      PlacementService placements, ProjectPlanning projects) {
    this.jdbc = jdbc;
    this.transaction = transaction;
    this.json = json;
    this.curriculum = curriculum;
    this.domains = domains;
    this.profile = profile;
    this.progress = progress;
    this.evidence = evidence;
    this.plans = plans;
    this.placements = placements;
    this.projects = projects;
  }

  /**
   * A unit, or (kind {@code project}) a question about one of the learner's projects: then
   * {@code unitId} is null, the title is the question's prompt and the project fields say where it
   * lives.
   */
  record ItemView(String unitId, String title, String type, int day, String kind, int minutes,
      String reason, boolean done, Long projectQuestionId, Long projectId, String projectName, String rung) {

    static ItemView unit(UnitSummary u, int day, String kind, int minutes, String reason, boolean done) {
      return new ItemView(u.id(), u.title(), u.type(), day, kind, minutes, reason, done, null, null, null, null);
    }

    static ItemView project(QuestionRef q, int day, int minutes, String reason, boolean done) {
      return new ItemView(null, q.prompt(), "project-question", day, "project", minutes, reason, done,
          q.questionId(), q.projectId(), q.projectName(), q.rung());
    }
  }

  record TopicToRate(String topicId, String name, String domainName, int currentGuess) {}

  /**
   * {@code canAddMore}: every item is done and there is something left to learn that fits the
   * largest extra, so the page can offer one.
   */
  record PlanView(int plannedMinutes, int goalMinutes, int doneMinutes, List<ItemView> items,
      List<Share> shares, List<String> notes, List<TopicToRate> topicsToRate, boolean canAddMore) {}

  /**
   * {@code plan} is null until the learner has said how much time they have and on which days, and
   * in a week they declared as a break, which gets no plan at all.
   */
  record Week(LocalDate weekStart, WeekSettings settings, PlanView plan, boolean onBreak) {}

  @GetMapping("/api/plan")
  Week thisWeek(@AuthenticationPrincipal LearnerPrincipal me) {
    LocalDate monday = PlanQueries.thisMonday();
    WeekSettings settings = profile.week(me.id());
    if (plans.breaks(me.id()).contains(monday)) {
      return new Week(monday, settings, null, true);
    }
    if (!settings.complete()) {
      return new Week(monday, settings, null, false);
    }
    Long planId = planId(me, monday);
    if (planId == null) {
      planId = generate(me, monday, settings, null, List.of());
    }
    return new Week(monday, settings, view(me, planId, monday, settings), false);
  }

  record SharePreview(Map<String, Integer> weights) {}

  /**
   * Each area's share of the week with these weights, computed exactly as the next plan would compute
   * it; nothing is saved. Weights are relative numbers, which are hard to reason about; the settings
   * form shows these percentages while the learner types, with each area's share before the weakness
   * factor and its strength, so it can say when being weaker in an area grew its share.
   */
  @PostMapping("/api/plan/shares")
  List<WeekPlanner.ShareDetail> previewShares(@AuthenticationPrincipal LearnerPrincipal me, @RequestBody SharePreview body) {
    Map<String, Integer> overrides = body == null || body.weights() == null ? Map.of() : body.weights();
    Set<String> known = new HashSet<>();
    domains.all().forEach(d -> known.add(d.id()));
    overrides.forEach((domain, weight) -> {
      if (!known.contains(domain) || weight == null || weight < 0 || weight > 100) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "weights are 0 to 100 for known areas");
      }
    });
    List<PlannableUnit> units = curriculum.plannable(me.slug());
    Map<String, TopicPlace> topics = curriculum.topicPlaces();
    List<Attempt> attempts = progress.attempts(me.id());
    Proficiency strength = proficiency(me, units, topics, attempts);
    Set<String> attempted = new HashSet<>();
    attempts.forEach(a -> attempted.add(a.unitId()));
    Map<String, String> placed = placements.effective(me);
    NotForMe notForMe = profile.notForMe(me.id());
    // The same test as a plan's candidates: not done yet, not placed "later", not "not for me".
    Set<String> withUnits = new HashSet<>();
    for (PlannableUnit u : units) {
      if (!attempted.contains(u.id()) && !Placements.LATER.equals(placed.get(u.id()))
          && !excluded(u, notForMe, topics)) {
        withUnits.add(u.domainId());
      }
    }
    List<WeekPlanner.Domain> planDomains = domains.all().stream()
        .map(d -> new WeekPlanner.Domain(d.id(), d.name(), overrides.getOrDefault(d.id(), d.weight()),
            strength.ofDomain(d.id())))
        .toList();
    return WeekPlanner.shareDetails(planDomains, withUnits);
  }

  /**
   * Redraws the week from today. Items already done this week are kept, so the week's done minutes,
   * goal and rewards do not drop; when there are none (or no plan can be made) the plan is simply
   * deleted and the next visit makes a new one, as on a first visit.
   */
  @DeleteMapping("/api/plan")
  void rebuild(@AuthenticationPrincipal LearnerPrincipal me) {
    LocalDate monday = PlanQueries.thisMonday();
    WeekSettings settings = profile.week(me.id());
    Long planId = planId(me, monday);
    List<Kept> kept = planId == null || !settings.complete() || plans.breaks(me.id()).contains(monday)
        ? List.of() : kept(me, planId, monday);
    if (kept.isEmpty()) {
      jdbc.update("delete from week_plan where learner_id = :learner and week_start = :week",
          new MapSqlParameterSource().addValue("learner", me.id()).addValue("week", monday));
      return;
    }
    generate(me, monday, settings, planId, kept);
  }

  record ExtraRequest(Integer minutes) {}

  private static final Set<Integer> EXTRA_MINUTES = Set.of(30, 60, 90);

  /**
   * More learning for a learner who finished this week's plan early, placed on today. Its minutes
   * are not added to the plan or its goal (see {@link WeekPlanner#extra}).
   */
  @PostMapping("/api/plan/extra")
  Week addMore(@AuthenticationPrincipal LearnerPrincipal me, @RequestBody ExtraRequest body) {
    if (body == null || body.minutes() == null || !EXTRA_MINUTES.contains(body.minutes())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minutes must be one of " + EXTRA_MINUTES);
    }
    LocalDate monday = PlanQueries.thisMonday();
    WeekSettings settings = profile.week(me.id());
    if (plans.breaks(me.id()).contains(monday)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This week is a break.");
    }
    Long planId = planId(me, monday);
    if (planId == null || !settings.complete()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "There is no plan this week yet.");
    }
    transaction.executeWithoutResult(status -> {
      // Locked, so a second click waits and then finds the first one's items not done yet.
      jdbc.query("select id from week_plan where id = :plan for update", new MapSqlParameterSource("plan", planId),
          rs -> {});
      if (!allDone(view(me, planId, monday, settings))) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Finish this week's plan first.");
      }
      List<Item> extra = extra(me, planId, settings, body.minutes());
      if (extra.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Nothing else to learn fits in " + body.minutes() + " minutes.");
      }
      for (Item item : extra) {
        jdbc.update("insert into plan_item (plan_id, unit_id, day, kind, minutes, reason, sort_order)"
                + " values (:plan, :unit, :day, :kind, :minutes, :reason,"
                + " (select coalesce(max(sort_order), 0) + 1 from plan_item where plan_id = :plan))",
            new MapSqlParameterSource().addValue("plan", planId).addValue("unit", item.unitId())
                .addValue("day", item.day()).addValue("kind", item.kind()).addValue("minutes", item.minutes())
                .addValue("reason", item.reason()));
      }
    });
    return thisWeek(me);
  }

  private static boolean allDone(PlanView plan) {
    return !plan.items().isEmpty() && plan.items().stream().allMatch(ItemView::done);
  }

  /** What {@link WeekPlanner#extra} would add to this plan in {@code minutes}, placed on today. */
  private List<Item> extra(LearnerPrincipal me, long planId, WeekSettings settings, int minutes) {
    Pool pool = pool(me, PlanQueries.thisMonday(), Set.of());
    Map<String, PlannableUnit> units = new HashMap<>();
    curriculum.plannable(me.slug()).forEach(u -> units.put(u.id(), u));
    Set<String> inPlan = new HashSet<>();
    Map<String, Integer> assigned = new HashMap<>();
    int[] heavy = {0};
    jdbc.query("select unit_id, kind, minutes from plan_item where plan_id = :plan and unit_id is not null",
        new MapSqlParameterSource("plan", planId), rs -> {
          String unit = rs.getString(1);
          inPlan.add(unit);
          PlannableUnit u = units.get(unit);
          if ("learn".equals(rs.getString(2)) && u != null) {
            assigned.merge(u.domainId(), rs.getInt(3), Integer::sum);
            if (rs.getInt(3) >= WeekPlanner.HEAVY_MINUTES) {
              heavy[0]++;
            }
          }
        });
    int today = LocalDate.now(STUDY_ZONE).getDayOfWeek().getValue();
    return WeekPlanner.extra(new WeekPlanner.Extra(today, minutes, planDomains(settings, pool.strength()),
        pool.candidates(), pool.byUnit().keySet(), inPlan, assigned, heavy[0]));
  }

  private Long planId(LearnerPrincipal me, LocalDate monday) {
    return jdbc.query("select id from week_plan where learner_id = :learner and week_start = :week",
        new MapSqlParameterSource().addValue("learner", me.id()).addValue("week", monday),
        rs -> rs.next() ? rs.getLong(1) : null);
  }

  /**
   * The learner's units sorted for planning: new ones they can learn ({@code candidates}), and
   * reviews due by the end of the week except for {@code skipReviews}. Units marked "not for me" are
   * in neither, and units placed "later" are not candidates.
   */
  private record Pool(List<Candidate> candidates, List<DueReview> reviews, Map<String, List<Attempt>> byUnit,
      Set<String> startNow, Proficiency strength) {}

  private Pool pool(LearnerPrincipal me, LocalDate monday, Set<String> skipReviews) {
    List<PlannableUnit> units = curriculum.plannable(me.slug());
    Map<String, TopicPlace> topics = curriculum.topicPlaces();
    List<Attempt> attempts = progress.attempts(me.id());
    Proficiency strength = proficiency(me, units, topics, attempts);
    Map<String, Integer> reports = evidence.reportsPerTopic();

    Map<String, List<Attempt>> byUnit = new LinkedHashMap<>();
    attempts.forEach(a -> byUnit.computeIfAbsent(a.unitId(), k -> new ArrayList<>()).add(a));
    Map<String, String> placed = placements.effective(me);
    Set<String> startNow = new HashSet<>();
    placed.forEach((unit, choice) -> {
      if (Placements.NOW.equals(choice)) {
        startNow.add(unit);
      }
    });
    List<Candidate> candidates = new ArrayList<>();
    List<DueReview> reviews = new ArrayList<>();
    NotForMe notForMe = profile.notForMe(me.id());
    for (PlannableUnit u : units) {
      if (excluded(u, notForMe, topics)) {
        continue;  // "Not for me": neither learned nor reviewed in this learner's plans
      }
      if (byUnit.containsKey(u.id())) {
        LocalDate due = ProgressQueries.dueOn(byUnit.get(u.id()));
        if (!due.isAfter(monday.plusDays(6)) && !skipReviews.contains(u.id())) {
          reviews.add(new DueReview(u.id(), u.type(), u.estMinutes(), due));
        }
      } else if (!Placements.LATER.equals(placed.get(u.id()))) {
        candidates.add(new Candidate(u.id(), u.title(), u.type(), u.topicId(), u.topicName(),
            u.domainId(), u.difficulty(), u.estMinutes(), u.prerequisites(),
            strength.of(u.topicId()).value(), reportsFor(u.topicId(), topics, reports)));
      }
    }
    return new Pool(candidates, reviews, byUnit, startNow, strength);
  }

  private List<WeekPlanner.Domain> planDomains(WeekSettings settings, Proficiency strength) {
    return domains.all().stream()
        .map(d -> new WeekPlanner.Domain(d.id(), d.name(),
            settings.weights().getOrDefault(d.id(), d.weight()), strength.ofDomain(d.id())))
        .toList();
  }

  /**
   * Makes this week's plan and saves it. On a rebuild, {@code replacing} is the plan it replaces, in
   * the same transaction, and {@code kept} that plan's done items, which go into the new one as they
   * were (their units and questions are not planned again) and add their minutes to it.
   */
  private long generate(LearnerPrincipal me, LocalDate monday, WeekSettings settings, Long replacing,
      List<Kept> kept) {
    Set<String> keptUnits = new HashSet<>();
    Set<Long> keptQuestions = new HashSet<>();
    int keptMinutes = 0;
    int keptToday = 0;
    LocalDate today = LocalDate.now(STUDY_ZONE);
    for (Kept k : kept) {
      if (k.item().unitId() != null) {
        keptUnits.add(k.item().unitId());
      } else {
        keptQuestions.add(k.item().projectQuestionId());
      }
      keptMinutes += k.item().minutes();
      if (k.doneOn().equals(today)) {
        keptToday += k.item().minutes();
      }
    }
    Pool pool = pool(me, monday, keptUnits);
    double load = loadFactor(me, monday);
    int fromDay = today.getDayOfWeek().getValue();
    List<WeekPlanner.ProjectQuestion> questions = projects.plannable(me.id()).stream()
        .filter(q -> !keptQuestions.contains(q.questionId()))
        .map(q -> new WeekPlanner.ProjectQuestion(q.questionId(), q.projectId(), q.projectName(),
            q.projectOrder(), q.questionOrder(), q.rung(), q.minutes(), q.dueOn()))
        .toList();
    List<Long> carried = questions.isEmpty() ? List.of()
        : carriedQuestions(me, monday).stream().filter(q -> !keptQuestions.contains(q)).toList();
    WeekPlanner.Plan plan = WeekPlanner.plan(new WeekPlanner.Input(monday, fromDay, settings.studyDays(),
        settings.hoursPerWeek(), load, planDomains(settings, pool.strength()), pool.candidates(),
        pool.byUnit().keySet(), pool.reviews(), pool.startNow(), questions, carried, keptToday));
    int planned = plan.plannedMinutes() + keptMinutes;
    int goal = kept.isEmpty() ? plan.goalMinutes() : (int) Math.round(planned * WeekPlanner.GOAL_SHARE);
    List<String> notes = new ArrayList<>(plan.notes());
    if (!kept.isEmpty()) {
      notes.add("Rebuilt: the " + (kept.size() == 1 ? "item" : kept.size() + " items")
          + " you had already done stay in it and count towards the week.");
    }
    List<Item> items = new ArrayList<>(kept.stream().map(Kept::item).toList());
    items.addAll(plan.items());
    items.sort(WeekPlanner.ORDER);

    try {
      return transaction.execute(status -> {
        if (replacing != null) {
          jdbc.update("delete from week_plan where id = :plan", new MapSqlParameterSource("plan", replacing));
        }
        long id = jdbc.queryForObject(
            "insert into week_plan (learner_id, week_start, planned_minutes, goal_minutes, load_factor,"
                + " release_id, planner_version, rationale) values (:learner, :week, :planned, :goal,"
                + " :load, :release, :version, cast(:rationale as jsonb)) returning id",
            new MapSqlParameterSource()
                .addValue("learner", me.id()).addValue("week", monday)
                .addValue("planned", planned).addValue("goal", goal)
                .addValue("load", load).addValue("release", curriculum.latestReleaseId())
                .addValue("version", WeekPlanner.VERSION)
                .addValue("rationale", json.writeValueAsString(
                    Map.of("shares", plan.shares(), "notes", notes))),
            Long.class);
        int order = 0;
        for (Item item : items) {
          jdbc.update(
              "insert into plan_item (plan_id, unit_id, project_question_id, day, kind, minutes, reason,"
                  + " sort_order) values (:plan, :unit, :question, :day, :kind, :minutes, :reason, :order)",
              new MapSqlParameterSource()
                  .addValue("plan", id).addValue("unit", item.unitId())
                  .addValue("question", item.projectQuestionId()).addValue("day", item.day())
                  .addValue("kind", item.kind()).addValue("minutes", item.minutes())
                  .addValue("reason", item.reason()).addValue("order", order++));
        }
        return id;
      });
    } catch (DuplicateKeyException e) {
      // Two tabs opened (or rebuilt) the week at once; the other one's plan stands.
      return planId(me, monday);
    }
  }

  /** An item of the plan being rebuilt, done this week: first on {@code doneOn}. */
  private record Kept(Item item, LocalDate doneOn) {}

  /**
   * The plan's items done this week, by the same test as the view and the week's result: the unit
   * got an attempt, or the project question a rating, during the week.
   */
  private List<Kept> kept(LearnerPrincipal me, long planId, LocalDate monday) {
    Map<String, LocalDate> unitDone = new HashMap<>();
    for (Attempt a : progress.attempts(me.id())) {
      if (PlanQueries.mondayOf(a.day()).equals(monday)) {
        unitDone.merge(a.unitId(), a.day(), (x, y) -> x.isBefore(y) ? x : y);
      }
    }
    Map<Long, LocalDate> questionDone = new HashMap<>();
    for (Rated r : projects.ratings(me.id())) {
      if (PlanQueries.mondayOf(r.day()).equals(monday)) {
        questionDone.merge(r.questionId(), r.day(), (x, y) -> x.isBefore(y) ? x : y);
      }
    }
    List<Kept> kept = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    jdbc.query("select unit_id, project_question_id, day, kind, minutes, reason from plan_item where plan_id = :plan"
            + " order by sort_order",
        new MapSqlParameterSource("plan", planId), rs -> {
          String unit = rs.getString(1);
          Long question = rs.getObject(2, Long.class);
          LocalDate doneOn = unit != null ? unitDone.get(unit) : questionDone.get(question);
          if (doneOn != null && seen.add(unit != null ? unit : "q" + question)) {
            kept.add(new Kept(new Item(unit, rs.getInt(3), rs.getString(4), rs.getInt(5), rs.getString(6), question),
                doneOn));
          }
        });
    return kept;
  }

  /**
   * Project questions from the learner's most recent earlier plan that were not rated during or
   * since its week, under the same two-carry limit as learning.
   */
  private List<Long> carriedQuestions(LearnerPrincipal me, LocalDate monday) {
    record Earlier(long id, LocalDate week) {}
    List<Earlier> earlier = jdbc.query(
        "select id, week_start from week_plan where learner_id = :learner and week_start < :week"
            + " order by week_start desc limit 2",
        new MapSqlParameterSource().addValue("learner", me.id()).addValue("week", monday),
        (rs, i) -> new Earlier(rs.getLong(1), rs.getObject(2, LocalDate.class)));
    if (earlier.isEmpty()) {
      return List.of();
    }
    Map<Long, Integer> timesCarried = new HashMap<>();
    jdbc.query("select project_question_id from plan_item where plan_id in (:plans) and kind = 'project'"
            + " and reason like :carried",
        new MapSqlParameterSource().addValue("plans", earlier.stream().map(Earlier::id).toList())
            .addValue("carried", WeekPlanner.CARRIED + "%"),
        rs -> {
          timesCarried.merge(rs.getLong(1), 1, Integer::sum);
        });
    LocalDate since = earlier.getFirst().week();
    Set<Long> ratedSince = new HashSet<>();
    projects.ratings(me.id()).stream().filter(r -> !r.day().isBefore(since))
        .forEach(r -> ratedSince.add(r.questionId()));
    return jdbc.queryForList(
            "select project_question_id from plan_item where plan_id = :plan and kind = 'project'"
                + " order by day, sort_order",
            new MapSqlParameterSource("plan", earlier.getFirst().id()), Long.class)
        .stream().filter(q -> !ratedSince.contains(q) && timesCarried.getOrDefault(q, 0) < 2).toList();
  }

  private Proficiency proficiency(LearnerPrincipal me, List<PlannableUnit> units,
      Map<String, TopicPlace> topics, List<Attempt> attempts) {
    Ratings ratings = profile.ratings(me.id());
    Map<String, String> lastRating = new HashMap<>();
    attempts.forEach(a -> lastRating.put(a.unitId(), a.rating()));  // oldest first, so the last wins
    Map<String, String> unitTopics = new HashMap<>();
    units.forEach(u -> unitTopics.put(u.id(), u.topicId()));
    return new Proficiency(topics, ratings.topics(), ratings.domains(), lastRating, unitTopics);
  }

  /** Excluded by its own id, by its topic, or by any topic above it. */
  static boolean excluded(PlannableUnit u, NotForMe notForMe, Map<String, TopicPlace> topics) {
    if (notForMe.units().contains(u.id())) {
      return true;
    }
    for (String t = u.topicId(); t != null; t = topics.containsKey(t) ? topics.get(t).parentId() : null) {
      if (notForMe.topics().contains(t)) {
        return true;
      }
    }
    return false;
  }

  /** Reports on the topic itself or on any topic above it: a report about "transactions" counts for sagas. */
  private static int reportsFor(String topicId, Map<String, TopicPlace> topics, Map<String, Integer> reports) {
    int total = 0;
    for (String t = topicId; t != null; t = topics.containsKey(t) ? topics.get(t).parentId() : null) {
      total += reports.getOrDefault(t, 0);
    }
    return total;
  }

  private double loadFactor(LearnerPrincipal me, LocalDate monday) {
    List<PlanQueries.WeekResult> before = plans.results(me.id()).stream()
        .filter(w -> w.weekStart().isBefore(monday)).toList();
    if (before.isEmpty()) {
      return 1.0;
    }
    double last = jdbc.queryForObject(
        "select load_factor from week_plan where learner_id = :learner and week_start = :week",
        new MapSqlParameterSource().addValue("learner", me.id())
            .addValue("week", before.getLast().weekStart()), Double.class);
    List<PlanQueries.WeekResult> lastTwo = before.subList(Math.max(0, before.size() - 2), before.size());
    if (lastTwo.size() == 2 && lastTwo.stream().allMatch(w -> w.done() < 0.6 * w.planned())) {
      return Math.max(0.3, last * SHRINK);
    }
    if (lastTwo.size() == 2 && lastTwo.stream().allMatch(PlanQueries.WeekResult::allDone)) {
      return Math.min(1.0, last * GROW);
    }
    return last;
  }

  private PlanView view(LearnerPrincipal me, long planId, LocalDate monday, WeekSettings settings) {
    Set<String> doneThisWeek = doneUnits(me, monday);
    List<ItemView> items = new ArrayList<>();
    List<String> learnUnits = new ArrayList<>();
    record Row(String unitId, Long questionId, int day, String kind, int minutes, String reason) {}
    List<Row> raw = jdbc.query(
        "select unit_id, project_question_id, day, kind, minutes, reason from plan_item where plan_id = :plan"
            + " order by sort_order",
        new MapSqlParameterSource("plan", planId),
        (rs, i) -> new Row(rs.getString("unit_id"), rs.getObject("project_question_id", Long.class),
            rs.getInt("day"), rs.getString("kind"), rs.getInt("minutes"), rs.getString("reason")));
    Map<String, UnitSummary> summaries = new HashMap<>();
    curriculum.summaries(raw.stream().map(Row::unitId).filter(Objects::nonNull).toList(), me.slug())
        .forEach(u -> summaries.put(u.id(), u));
    List<Long> questionIds = raw.stream().map(Row::questionId).filter(Objects::nonNull).toList();
    Map<Long, QuestionRef> questions = projects.describe(me.id(), questionIds);
    Set<Long> ratedThisWeek = new HashSet<>();
    if (!questionIds.isEmpty()) {
      for (Rated r : projects.ratings(me.id())) {
        if (PlanQueries.mondayOf(r.day()).equals(monday)) {
          ratedThisWeek.add(r.questionId());
        }
      }
    }
    int doneMinutes = 0;
    for (Row r : raw) {
      if (r.questionId() != null) {
        QuestionRef q = questions.get(r.questionId());
        if (q == null) {
          continue;  // retired since the plan was made
        }
        boolean done = ratedThisWeek.contains(q.questionId());
        if (done) {
          doneMinutes += r.minutes();
        }
        items.add(ItemView.project(q, r.day(), r.minutes(), r.reason(), done));
        continue;
      }
      UnitSummary u = summaries.get(r.unitId());
      if (u == null) {
        continue;  // retired or hidden since the plan was made
      }
      boolean done = doneThisWeek.contains(u.id());
      if (done) {
        doneMinutes += r.minutes();
      }
      if ("learn".equals(r.kind())) {
        learnUnits.add(u.id());
      }
      items.add(ItemView.unit(u, r.day(), r.kind(), r.minutes(), r.reason(), done));
    }
    record Header(int planned, int goal, JsonNode rationale) {}
    Header header = jdbc.queryForObject(
        "select planned_minutes, goal_minutes, rationale::text from week_plan where id = :plan",
        new MapSqlParameterSource("plan", planId),
        (rs, i) -> new Header(rs.getInt(1), rs.getInt(2), json.readTree(rs.getString(3))));
    List<Share> shares = new ArrayList<>();
    header.rationale().path("shares").forEach(s -> shares.add(new Share(s.path("domainId").asString(),
        s.path("name").asString(), s.path("percent").asInt())));
    List<String> notes = new ArrayList<>();
    header.rationale().path("notes").forEach(n -> notes.add(n.asString()));
    boolean allDone = !items.isEmpty() && items.stream().allMatch(ItemView::done);
    // Only worked out once everything is done: the common case pays nothing for it.
    boolean canAddMore = allDone && !extra(me, planId, settings, Collections.max(EXTRA_MINUTES)).isEmpty();
    return new PlanView(header.planned(), header.goal(), doneMinutes, items, shares, notes,
        topicsToRate(me, learnUnits), canAddMore);
  }

  /**
   * The weekly "how are you with these?" card: topics this week teaches whose strength is only a
   * guess from the domain rating. Answering sharpens next week's plan; it never blocks this one.
   */
  private List<TopicToRate> topicsToRate(LearnerPrincipal me, List<String> learnUnits) {
    List<PlannableUnit> units = curriculum.plannable(me.slug());
    Map<String, TopicPlace> topics = curriculum.topicPlaces();
    Proficiency strength = proficiency(me, units, topics, progress.attempts(me.id()));
    Map<String, String> domainNames = new HashMap<>();
    domains.all().forEach(d -> domainNames.put(d.id(), d.name()));
    Set<String> asked = new LinkedHashSet<>();
    List<TopicToRate> out = new ArrayList<>();
    Set<String> planned = new HashSet<>(learnUnits);
    for (PlannableUnit u : units) {
      if (planned.contains(u.id()) && out.size() < MAX_TOPICS_TO_ASK && asked.add(u.topicId())) {
        Proficiency.Resolved r = strength.of(u.topicId());
        if (r.guessed()) {
          out.add(new TopicToRate(u.topicId(), u.topicName(), domainNames.get(u.domainId()),
              (int) Math.round(r.value())));
        }
      }
    }
    return out;
  }

  /** Units the learner recorded an attempt on during the week: that is what "done" means here. */
  private Set<String> doneUnits(LearnerPrincipal me, LocalDate monday) {
    Set<String> done = new HashSet<>();
    for (Attempt a : progress.attempts(me.id())) {
      if (!a.day().isBefore(monday) && a.day().isBefore(monday.plusDays(7))) {
        done.add(a.unitId());
      }
    }
    return done;
  }
}
