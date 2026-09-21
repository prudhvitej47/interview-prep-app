package com.interviewprep.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewprep.curriculum.CurriculumQueries.TopicPlace;
import com.interviewprep.planner.Proficiency.Source;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProficiencyTest {

  private final Map<String, TopicPlace> topics = new HashMap<>(Map.of(
      "db.sql", new TopicPlace("SQL", null, "databases"),
      "db.sql.joins", new TopicPlace("Joins", "db.sql", "databases"),
      "db.indexes", new TopicPlace("Indexes", null, "databases"),
      "dsa.graphs", new TopicPlace("Graphs", null, "dsa")));

  private Proficiency with(Map<String, Integer> topicRatings, Map<String, String> lastRatings) {
    return new Proficiency(topics, topicRatings, Map.of("databases", 2), lastRatings,
        Map.of("db.sql.joins.u1", "db.sql.joins", "db.sql.joins.u2", "db.sql.joins"));
  }

  @Test
  void anUnratedTopicInheritsItsDomainRating() {
    assertThat(with(Map.of(), Map.of()).of("db.indexes")).isEqualTo(new Proficiency.Resolved(2, Source.DOMAIN_RATING));
  }

  @Test
  void aTopicWithNoRatedDomainIsNeutral() {
    assertThat(with(Map.of(), Map.of()).of("dsa.graphs")).isEqualTo(new Proficiency.Resolved(2.5, Source.DEFAULT));
  }

  @Test
  void aSubtopicInheritsItsParentsRatingBeforeTheDomains() {
    assertThat(with(Map.of("db.sql", 4), Map.of()).of("db.sql.joins").value()).isEqualTo(4);
  }

  @Test
  void progressBeatsEveryRatingAndAveragesTheLastRatingOfEachUnit() {
    Proficiency.Resolved r = with(Map.of("db.sql.joins", 1), Map.of("db.sql.joins.u1", "easy",
        "db.sql.joins.u2", "hard")).of("db.sql.joins");
    assertThat(r).isEqualTo(new Proficiency.Resolved(3.25, Source.PROGRESS));
    assertThat(r.guessed()).isFalse();
  }

  @Test
  void newTopicsFromAReleaseLeaveTheDomainsStrengthAlone() {
    double before = with(Map.of(), Map.of()).ofDomain("databases");
    for (int i = 0; i < 20; i++) {
      topics.put("db.new" + i, new TopicPlace("New " + i, null, "databases"));
    }
    assertThat(with(Map.of(), Map.of()).ofDomain("databases")).isEqualTo(before);
  }
}
