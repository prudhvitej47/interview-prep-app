package com.interviewprep.planner;

import static com.interviewprep.progress.ProgressQueries.STUDY_ZONE;

import com.interviewprep.curriculum.CurriculumQueries;
import com.interviewprep.curriculum.CurriculumQueries.PlannableUnit;
import com.interviewprep.curriculum.CurriculumQueries.Release;
import com.interviewprep.curriculum.CurriculumQueries.ReleaseUnit;
import com.interviewprep.curriculum.CurriculumQueries.TopicPlace;
import com.interviewprep.curriculum.DomainCatalog;
import com.interviewprep.curriculum.DomainSummary;
import com.interviewprep.learner.LearnerPrincipal;
import com.interviewprep.learner.LearnerProfile;
import com.interviewprep.learner.LearnerProfile.Ratings;
import com.interviewprep.progress.ProgressQueries;
import com.interviewprep.progress.ProgressQueries.Attempt;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The home dashboard: coverage by area, the weakest topics that still have something to learn, and
 * what the latest release changed, with each new unit's placement for this learner.
 */
@RestController
class DashboardController {

  static final String NOW_REASON = "You chose to start it now";
  private static final int WEAK_AREAS = 3;

  private final NamedParameterJdbcTemplate jdbc;
  private final CurriculumQueries curriculum;
  private final DomainCatalog domains;
  private final LearnerProfile profile;
  private final ProgressQueries progress;
  private final PlacementService placements;

  DashboardController(NamedParameterJdbcTemplate jdbc, CurriculumQueries curriculum,
      DomainCatalog domains, LearnerProfile profile, ProgressQueries progress,
      PlacementService placements) {
    this.jdbc = jdbc;
    this.curriculum = curriculum;
    this.domains = domains;
    this.profile = profile;
    this.progress = progress;
    this.placements = placements;
  }

  record Coverage(String domainId, String name, int done, int total) {}

  record WeakArea(String topicId, String name, String domainName, double strength, int unitsLeft) {}

  record NewUnit(String unitId, String title, String type, boolean added, String placement,
      String suggested, boolean chosen) {}

  record WhatChanged(String version, OffsetDateTime releasedAt, String changelog, int added,
      int changed, int retired, List<NewUnit> units) {}

  record Dashboard(List<Coverage> coverage, List<WeakArea> weakAreas, WhatChanged whatChanged) {}

  record PlacementRequest(String unitId, String choice) {}

  @GetMapping("/api/dashboard")
  Dashboard dashboard(@AuthenticationPrincipal LearnerPrincipal me) {
    List<PlannableUnit> units = curriculum.plannable(me.slug());
    Map<String, TopicPlace> topics = curriculum.topicPlaces();
    List<Attempt> attempts = progress.attempts(me.id());
    Set<String> done = new HashSet<>();
    Map<String, String> lastRating = new HashMap<>();
    attempts.forEach(a -> {
      done.add(a.unitId());
      lastRating.put(a.unitId(), a.rating());
    });
    Map<String, String> unitTopics = new HashMap<>();
    units.forEach(u -> unitTopics.put(u.id(), u.topicId()));
    Ratings ratings = profile.ratings(me.id());
    Proficiency strength = new Proficiency(topics, ratings.topics(), ratings.domains(), lastRating, unitTopics);

    // Coverage: only areas that have units, in curriculum order.
    List<Coverage> coverage = new ArrayList<>();
    Map<String, String> domainNames = new HashMap<>();
    for (DomainSummary d : domains.all()) {
      domainNames.put(d.id(), d.name());
      List<PlannableUnit> in = units.stream().filter(u -> u.domainId().equals(d.id())).toList();
      if (!in.isEmpty()) {
        coverage.add(new Coverage(d.id(), d.name(), (int) in.stream().filter(u -> done.contains(u.id())).count(),
            in.size()));
      }
    }

    // Weak areas: the weakest topics that still have units to learn, so each is actionable.
    Map<String, Integer> left = new HashMap<>();
    units.stream().filter(u -> !done.contains(u.id())).forEach(u -> left.merge(u.topicId(), 1, Integer::sum));
    List<WeakArea> weak = left.entrySet().stream()
        .map(e -> new WeakArea(e.getKey(), topics.get(e.getKey()).name(),
            domainNames.get(topics.get(e.getKey()).domainId()), strength.of(e.getKey()).value(), e.getValue()))
        .sorted(Comparator.comparingDouble(WeakArea::strength).thenComparing(WeakArea::name))
        .limit(WEAK_AREAS).toList();

    Release release = curriculum.latestRelease(me.slug());
    WhatChanged changed = null;
    if (release != null) {
      Map<String, PlacementService.Choice> placed = placements.forLatestRelease(me);
      List<NewUnit> newUnits = new ArrayList<>();
      for (ReleaseUnit u : release.units()) {
        PlacementService.Choice c = placed.get(u.id());
        newUnits.add(new NewUnit(u.id(), u.title(), u.type(), u.added(), c.choice(), c.suggested(), c.chosen()));
      }
      changed = new WhatChanged(release.version(), release.createdAt(), release.changelog(),
          release.added(), release.changed(), release.retired(), newUnits);
    }
    return new Dashboard(coverage, weak, changed);
  }

  /**
   * Places a unit from the latest release. "Now" also adds it to this week's plan if there is one
   * (on the least busy study day left); moving it away from "now" takes that item back out.
   */
  @PutMapping("/api/placements")
  @Transactional
  Dashboard place(@AuthenticationPrincipal LearnerPrincipal me, @RequestBody PlacementRequest request) {
    Set<String> choices = Set.of(Placements.NOW, Placements.NEXT_WEEK, Placements.LATER);
    if (request == null || !choices.contains(request.choice())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "choice must be one of " + choices);
    }
    Release release = curriculum.latestRelease(me.slug());
    if (release == null || release.units().stream().noneMatch(u -> u.id().equals(request.unitId()))) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not a unit of the latest release");
    }
    MapSqlParameterSource p = new MapSqlParameterSource().addValue("learner", me.id())
        .addValue("unit", request.unitId()).addValue("release", release.id())
        .addValue("choice", request.choice()).addValue("week", PlanQueries.thisMonday());
    jdbc.update("insert into placement (learner_id, unit_id, release_id, choice, target_week)"
        + " values (:learner, :unit, :release, :choice, :week)"
        + " on conflict (learner_id, unit_id, release_id)"
        + " do update set choice = excluded.choice, target_week = excluded.target_week, decided_at = now()", p);

    Long plan = jdbc.query("select id from week_plan where learner_id = :learner and week_start = :week", p,
        rs -> rs.next() ? rs.getLong(1) : null);
    if (plan != null) {
      p.addValue("plan", plan).addValue("reason", NOW_REASON).addValue("reasonPrefix", NOW_REASON + "%");
      if (request.choice().equals(Placements.NOW)) {
        addToThisWeek(me, p, plan, request.unitId());
      } else {
        // Whether added here or put first when the plan was made, the item's reason starts the same way.
        jdbc.update("delete from plan_item where plan_id = :plan and unit_id = :unit and reason like :reasonPrefix", p);
      }
    }
    return dashboard(me);
  }

  private void addToThisWeek(LearnerPrincipal me, MapSqlParameterSource p, long plan, String unitId) {
    Boolean already = jdbc.queryForObject(
        "select exists(select 1 from plan_item where plan_id = :plan and unit_id = :unit)", p, Boolean.class);
    PlannableUnit unit = curriculum.plannable(me.slug()).stream().filter(u -> u.id().equals(unitId))
        .findFirst().orElse(null);
    if (Boolean.TRUE.equals(already) || unit == null) {
      return;
    }
    int today = LocalDate.now(STUDY_ZONE).getDayOfWeek().getValue();
    List<Integer> days = profile.week(me.id()).studyDays().stream().filter(d -> d >= today).toList();
    Map<Integer, Integer> load = new HashMap<>();
    jdbc.query("select day, sum(minutes) from plan_item where plan_id = :plan group by day", p, rs -> {
      load.put(rs.getInt(1), rs.getInt(2));
    });
    int day = days.stream().min(Comparator.comparingInt((Integer d) -> load.getOrDefault(d, 0))
        .thenComparingInt(d -> d)).orElse(today);
    jdbc.update("insert into plan_item (plan_id, unit_id, day, kind, minutes, reason, sort_order)"
        + " values (:plan, :unit, :day, 'learn', :minutes, :reason,"
        + " (select coalesce(max(sort_order), 0) + 1 from plan_item where plan_id = :plan))",
        p.addValue("day", day).addValue("minutes", unit.estMinutes()));
  }
}
