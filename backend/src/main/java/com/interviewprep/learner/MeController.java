package com.interviewprep.learner;

import com.interviewprep.curriculum.CurriculumQueries;
import com.interviewprep.curriculum.DomainCatalog;
import com.interviewprep.curriculum.DomainSummary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The signed-in learner, and the onboarding self-ratings.
 *
 * <p>Onboarding asks for one 0–5 rating per domain — twelve questions, not the 174 topics. Only what
 * a learner actually said is stored; topics inherit their domain's rating when read, so topics added
 * by a later curriculum release need no new questions and no backfill.
 */
@RestController
@RequestMapping("/api/me")
class MeController {

  private final NamedParameterJdbcTemplate jdbc;
  private final DomainCatalog domains;
  private final JsonMapper json;
  private final CurriculumQueries curriculum;

  MeController(NamedParameterJdbcTemplate jdbc, DomainCatalog domains, JsonMapper json,
      CurriculumQueries curriculum) {
    this.jdbc = jdbc;
    this.domains = domains;
    this.json = json;
    this.curriculum = curriculum;
  }

  record Me(String slug, String displayName, boolean onboarded, Map<String, Integer> domainRatings) {}

  record DomainRatings(Map<String, Integer> ratings) {}

  @GetMapping
  Me me(@AuthenticationPrincipal LearnerPrincipal me) {
    return describe(me);
  }

  @PutMapping("/ratings/domains")
  Me rateDomains(@AuthenticationPrincipal LearnerPrincipal me, @RequestBody DomainRatings body) {
    Map<String, Integer> ratings = body == null || body.ratings() == null ? Map.of() : body.ratings();
    Set<String> expected = domainIds();
    if (expected.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "no curriculum is loaded yet");
    }
    // All of them, and only them: a partial set would leave the planner guessing about the rest,
    // and an unknown id would store a rating nothing ever reads.
    if (!ratings.keySet().equals(expected)) {
      Set<String> missing = new TreeSet<>(expected);
      missing.removeAll(ratings.keySet());
      Set<String> unknown = new TreeSet<>(ratings.keySet());
      unknown.removeAll(expected);
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "rate every domain exactly once; missing " + missing + ", unknown " + unknown);
    }
    ratings.forEach((domain, rating) -> {
      if (rating == null || rating < 0 || rating > 5) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "ratings are 0 to 5; " + domain + " was " + rating);
      }
    });

    // One statement, so it is atomic without a transaction around it. Re-rating replaces the
    // earlier answer rather than piling up history; real progress supersedes these soon anyway.
    jdbc.update(
        "insert into learner_competency (learner_id, scope, scope_id, rating, source)"
            + " select :learner, 'domain', key, value::smallint, 'self-rating'"
            + " from jsonb_each_text(cast(:ratings as jsonb))"
            + " on conflict (learner_id, scope, scope_id)"
            + " do update set rating = excluded.rating, source = excluded.source, rated_at = now()",
        new MapSqlParameterSource()
            .addValue("learner", me.id())
            .addValue("ratings", json.writeValueAsString(ratings)));
    return describe(me);
  }

  /**
   * Sharpens some topics' ratings, typically from the week plan's "how are you with these?" card.
   * Only the topics sent are touched; the rest keep inheriting from their domain.
   */
  @PutMapping("/ratings/topics")
  Me rateTopics(@AuthenticationPrincipal LearnerPrincipal me, @RequestBody DomainRatings body) {
    Map<String, Integer> ratings = body == null || body.ratings() == null ? Map.of() : body.ratings();
    Set<String> known = curriculum.topicPlaces().keySet();
    ratings.forEach((topic, rating) -> {
      if (!known.contains(topic)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown topic " + topic);
      }
      if (rating == null || rating < 0 || rating > 5) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "ratings are 0 to 5; " + topic + " was " + rating);
      }
    });
    jdbc.update(
        "insert into learner_competency (learner_id, scope, scope_id, rating, source)"
            + " select :learner, 'topic', key, value::smallint, 'self-rating'"
            + " from jsonb_each_text(cast(:ratings as jsonb))"
            + " on conflict (learner_id, scope, scope_id)"
            + " do update set rating = excluded.rating, source = excluded.source, rated_at = now()",
        new MapSqlParameterSource()
            .addValue("learner", me.id())
            .addValue("ratings", json.writeValueAsString(ratings)));
    return describe(me);
  }

  private Me describe(LearnerPrincipal me) {
    Map<String, Integer> ratings = new LinkedHashMap<>();
    jdbc.query(
        "select scope_id, rating from learner_competency"
            + " where learner_id = :learner and scope = 'domain' order by scope_id",
        new MapSqlParameterSource("learner", me.id()),
        rs -> {
          ratings.put(rs.getString("scope_id"), rs.getInt("rating"));
        });
    Set<String> expected = domainIds();
    // Nothing to be onboarded against until a curriculum is loaded.
    boolean onboarded = !expected.isEmpty() && ratings.keySet().containsAll(expected);
    return new Me(me.slug(), me.displayName(), onboarded, ratings);
  }

  private Set<String> domainIds() {
    List<DomainSummary> all = domains.all();
    return all.stream().map(DomainSummary::id).collect(Collectors.toSet());
  }
}
