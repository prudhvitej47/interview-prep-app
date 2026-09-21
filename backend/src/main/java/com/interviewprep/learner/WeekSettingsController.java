package com.interviewprep.learner;

import com.interviewprep.curriculum.DomainCatalog;
import com.interviewprep.curriculum.DomainSummary;
import com.interviewprep.learner.LearnerProfile.WeekSettings;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** How much time each learner has, on which days, and how they want it split across domains. */
@RestController
@RequestMapping("/api/me/week")
class WeekSettingsController {

  private final NamedParameterJdbcTemplate jdbc;
  private final LearnerProfile profile;
  private final DomainCatalog domains;

  WeekSettingsController(NamedParameterJdbcTemplate jdbc, LearnerProfile profile, DomainCatalog domains) {
    this.jdbc = jdbc;
    this.profile = profile;
    this.domains = domains;
  }

  @GetMapping
  WeekSettings read(@AuthenticationPrincipal LearnerPrincipal me) {
    return profile.week(me.id());
  }

  /** Replaces all three. An empty weights map goes back to the curriculum's defaults. */
  @PutMapping
  @Transactional
  WeekSettings save(@AuthenticationPrincipal LearnerPrincipal me, @RequestBody WeekSettings body) {
    if (body == null || body.hoursPerWeek() == null || body.hoursPerWeek() < 1 || body.hoursPerWeek() > 40) {
      throw bad("hours per week must be between 1 and 40");
    }
    List<Integer> days = body.studyDays() == null ? List.of() : body.studyDays().stream().distinct().sorted().toList();
    if (days.isEmpty() || days.stream().anyMatch(d -> d == null || d < 1 || d > 7)) {
      throw bad("study days are 1 (Monday) to 7 (Sunday), at least one");
    }
    Map<String, Integer> weights = body.weights() == null ? Map.of() : body.weights();
    Set<String> known = domains.all().stream().map(DomainSummary::id).collect(Collectors.toSet());
    weights.forEach((domain, weight) -> {
      if (!known.contains(domain)) {
        throw bad("unknown domain " + domain);
      }
      if (weight == null || weight < 0 || weight > 100) {
        throw bad("weights are 0 to 100; " + domain + " was " + weight);
      }
    });

    MapSqlParameterSource p = new MapSqlParameterSource("learner", me.id());
    jdbc.update("update learner set hours_per_week = :hours, study_days = cast(:days as smallint[])"
        + " where id = :learner",
        p.addValue("hours", body.hoursPerWeek())
            .addValue("days", "{" + days.stream().map(String::valueOf).collect(Collectors.joining(",")) + "}"));
    jdbc.update("delete from learner_weight where learner_id = :learner", p);
    weights.forEach((domain, weight) -> jdbc.update(
        "insert into learner_weight (learner_id, domain_id, weight) values (:learner, :domain, :weight)",
        new MapSqlParameterSource().addValue("learner", me.id()).addValue("domain", domain)
            .addValue("weight", weight)));
    return profile.week(me.id());
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
