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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * This week's plan. It is made the first time the week is opened — there is no Monday job to miss
 * while the VM restarts — and then stays as it is: a curriculum release mid-week changes next week,
 * not this one (proposal F10). "Rebuild" throws it away so the next visit makes a new one, for
 * after changing hours or weights.
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

  PlanController(NamedParameterJdbcTemplate jdbc, TransactionTemplate transaction, JsonMapper json,
      CurriculumQueries curriculum, DomainCatalog domains, LearnerProfile profile,
      ProgressQueries progress, EvidenceQueries evidence, PlanQueries plans,
      PlacementService placements) {
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
  }

  record ItemView(String unitId, String title, String type, int day, String kind, int minutes,
      String reason, boolean done) {}

  record TopicToRate(String topicId, String name, String domainName, int currentGuess) {}

  record PlanView(int plannedMinutes, int goalMinutes, int doneMinutes, List<ItemView> items,
      List<Share> shares, List<String> notes, List<TopicToRate> topicsToRate) {}

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
      planId = generate(me, monday, settings);
    }
    return new Week(monday, settings, view(me, planId, monday), false);
  }

  @DeleteMapping("/api/plan")
  void rebuild(@AuthenticationPrincipal LearnerPrincipal me) {
    LocalDate monday = PlanQueries.thisMonday();
    jdbc.update("delete from week_plan where learner_id = :learner and week_start = :week",
        new MapSqlParameterSource().addValue("learner", me.id()).addValue("week", monday));
  }

  private Long planId(LearnerPrincipal me, LocalDate monday) {
    return jdbc.query("select id from week_plan where learner_id = :learner and week_start = :week",
        new MapSqlParameterSource().addValue("learner", me.id()).addValue("week", monday),
        rs -> rs.next() ? rs.getLong(1) : null);
  }

  private long generate(LearnerPrincipal me, LocalDate monday, WeekSettings settings) {
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
        if (!due.isAfter(monday.plusDays(6))) {
          reviews.add(new DueReview(u.id(), u.type(), u.estMinutes(), due));
        }
      } else if (!Placements.LATER.equals(placed.get(u.id()))) {
        candidates.add(new Candidate(u.id(), u.title(), u.type(), u.topicId(), u.topicName(),
            u.domainId(), u.difficulty(), u.estMinutes(), u.prerequisites(),
            strength.of(u.topicId()).value(), reportsFor(u.topicId(), topics, reports)));
      }
    }
    List<WeekPlanner.Domain> planDomains = domains.all().stream()
        .map(d -> new WeekPlanner.Domain(d.id(), d.name(),
            settings.weights().getOrDefault(d.id(), d.weight()), strength.ofDomain(d.id())))
        .toList();
    double load = loadFactor(me, monday);
    int fromDay = LocalDate.now(STUDY_ZONE).getDayOfWeek().getValue();
    WeekPlanner.Plan plan = WeekPlanner.plan(new WeekPlanner.Input(monday, fromDay, settings.studyDays(),
        settings.hoursPerWeek(), load, planDomains, candidates, byUnit.keySet(), reviews, startNow,
        carryOver(me, monday)));

    try {
      return transaction.execute(status -> {
        long id = jdbc.queryForObject(
            "insert into week_plan (learner_id, week_start, planned_minutes, goal_minutes, load_factor,"
                + " release_id, planner_version, rationale) values (:learner, :week, :planned, :goal,"
                + " :load, :release, :version, cast(:rationale as jsonb)) returning id",
            new MapSqlParameterSource()
                .addValue("learner", me.id()).addValue("week", monday)
                .addValue("planned", plan.plannedMinutes()).addValue("goal", plan.goalMinutes())
                .addValue("load", load).addValue("release", curriculum.latestReleaseId())
                .addValue("version", WeekPlanner.VERSION)
                .addValue("rationale", json.writeValueAsString(
                    Map.of("shares", plan.shares(), "notes", plan.notes()))),
            Long.class);
        int order = 0;
        for (Item item : plan.items()) {
          jdbc.update(
              "insert into plan_item (plan_id, unit_id, day, kind, minutes, reason, sort_order)"
                  + " values (:plan, :unit, :day, :kind, :minutes, :reason, :order)",
              new MapSqlParameterSource()
                  .addValue("plan", id).addValue("unit", item.unitId()).addValue("day", item.day())
                  .addValue("kind", item.kind()).addValue("minutes", item.minutes())
                  .addValue("reason", item.reason()).addValue("order", order++));
        }
        return id;
      });
    } catch (DuplicateKeyException e) {
      // Two tabs opened the new week at once; the other one's plan stands.
      return planId(me, monday);
    }
  }

  /**
   * Learning items from the learner's most recent earlier plan, in the order they were planned. It is
   * the most recent plan, not strictly last week's, so a break or a missed week does not lose them.
   * An item that has already been carried over twice is left to the normal ranking, so one skipped
   * unit cannot hold a place in every plan. Items done since are filtered out by the planner, which
   * only sees units with no attempt.
   */
  private List<String> carryOver(LearnerPrincipal me, LocalDate monday) {
    List<Long> earlier = jdbc.queryForList(
        "select id from week_plan where learner_id = :learner and week_start < :week"
            + " order by week_start desc limit 2",
        new MapSqlParameterSource().addValue("learner", me.id()).addValue("week", monday), Long.class);
    if (earlier.isEmpty()) {
      return List.of();
    }
    Map<String, Integer> timesCarried = new HashMap<>();
    jdbc.query("select unit_id from plan_item where plan_id in (:plans) and kind = 'learn'"
            + " and reason like :carried",
        new MapSqlParameterSource().addValue("plans", earlier)
            .addValue("carried", WeekPlanner.CARRIED + "%"),
        rs -> {
          timesCarried.merge(rs.getString(1), 1, Integer::sum);
        });
    return jdbc.queryForList(
            "select unit_id from plan_item where plan_id = :plan and kind = 'learn' order by day, sort_order",
            new MapSqlParameterSource("plan", earlier.getFirst()), String.class)
        .stream().filter(u -> timesCarried.getOrDefault(u, 0) < 2).toList();
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
  private static boolean excluded(PlannableUnit u, NotForMe notForMe, Map<String, TopicPlace> topics) {
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

  private PlanView view(LearnerPrincipal me, long planId, LocalDate monday) {
    Set<String> doneThisWeek = doneUnits(me, monday);
    List<ItemView> items = new ArrayList<>();
    List<String> learnUnits = new ArrayList<>();
    List<Object[]> raw = jdbc.query(
        "select unit_id, day, kind, minutes, reason from plan_item where plan_id = :plan order by sort_order",
        new MapSqlParameterSource("plan", planId),
        (rs, i) -> new Object[] {rs.getString("unit_id"), rs.getInt("day"), rs.getString("kind"),
            rs.getInt("minutes"), rs.getString("reason")});
    Map<String, UnitSummary> summaries = new HashMap<>();
    curriculum.summaries(raw.stream().map(r -> (String) r[0]).toList(), me.slug())
        .forEach(u -> summaries.put(u.id(), u));
    int doneMinutes = 0;
    for (Object[] r : raw) {
      UnitSummary u = summaries.get((String) r[0]);
      if (u == null) {
        continue;  // retired or hidden since the plan was made
      }
      boolean done = doneThisWeek.contains(u.id());
      if (done) {
        doneMinutes += (int) r[3];
      }
      if ("learn".equals(r[2])) {
        learnUnits.add(u.id());
      }
      items.add(new ItemView(u.id(), u.title(), u.type(), (int) r[1], (String) r[2], (int) r[3],
          (String) r[4], done));
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
    return new PlanView(header.planned(), header.goal(), doneMinutes, items, shares, notes,
        topicsToRate(me, learnUnits));
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
