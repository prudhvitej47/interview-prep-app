package com.interviewprep.planner;

import com.interviewprep.curriculum.CurriculumQueries.TopicPlace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How strong a learner is in a topic, 0 to 5, from the best evidence there is (plan section 5.6):
 *
 * <ol>
 *   <li>their own progress: the last rating on each unit they have done in the topic, averaged;
 *   <li>their self-rating of the topic;
 *   <li>the same two for the parent topic, and so on up;
 *   <li>their onboarding rating of the domain;
 *   <li>2.5, neutral.
 * </ol>
 *
 * <p>So a topic added by a later release starts at its domain's rating, not at zero: a release that
 * adds twenty topics does not make them all look like urgent weaknesses.
 */
final class Proficiency {

  enum Source { PROGRESS, TOPIC_RATING, DOMAIN_RATING, DEFAULT }

  record Resolved(double value, Source source) {
    /** Nothing specific was ever said about this topic, so it is worth asking. */
    boolean guessed() {
      return source == Source.DOMAIN_RATING || source == Source.DEFAULT;
    }
  }

  static final double NEUTRAL = 2.5;

  // What each rating says about strength. "Good" is solid but not yet interview-proof.
  private static final Map<String, Double> STRENGTH =
      Map.of("again", 1.0, "hard", 2.0, "good", 3.5, "easy", 4.5);

  private final Map<String, TopicPlace> topics;
  private final Map<String, Integer> topicRatings;
  private final Map<String, Integer> domainRatings;
  private final Map<String, Double> progress = new HashMap<>();

  /**
   * @param lastRatingByUnit the latest rating of each unit the learner has done
   * @param unitTopics the topic of each unit
   */
  Proficiency(Map<String, TopicPlace> topics, Map<String, Integer> topicRatings,
      Map<String, Integer> domainRatings, Map<String, String> lastRatingByUnit,
      Map<String, String> unitTopics) {
    this.topics = topics;
    this.topicRatings = topicRatings;
    this.domainRatings = domainRatings;
    Map<String, List<Double>> byTopic = new HashMap<>();
    lastRatingByUnit.forEach((unit, rating) -> {
      String topic = unitTopics.get(unit);
      if (topic != null) {
        byTopic.computeIfAbsent(topic, t -> new ArrayList<>()).add(STRENGTH.get(rating));
      }
    });
    byTopic.forEach((topic, values) ->
        progress.put(topic, values.stream().mapToDouble(Double::doubleValue).average().orElseThrow()));
  }

  Resolved of(String topicId) {
    String domain = null;
    for (String t = topicId; t != null; t = topics.containsKey(t) ? topics.get(t).parentId() : null) {
      if (progress.containsKey(t)) {
        return new Resolved(progress.get(t), Source.PROGRESS);
      }
      if (topicRatings.containsKey(t)) {
        return new Resolved(topicRatings.get(t), Source.TOPIC_RATING);
      }
      if (topics.containsKey(t)) {
        domain = topics.get(t).domainId();
      }
    }
    if (domain != null && domainRatings.containsKey(domain)) {
      return new Resolved(domainRatings.get(domain), Source.DOMAIN_RATING);
    }
    return new Resolved(NEUTRAL, Source.DEFAULT);
  }

  /** A domain's strength: the average over all its topics, so progress anywhere in it counts. */
  double ofDomain(String domainId) {
    return topics.entrySet().stream()
        .filter(e -> domainId.equals(e.getValue().domainId()))
        .mapToDouble(e -> of(e.getKey()).value())
        .average()
        .orElse(domainRatings.containsKey(domainId) ? domainRatings.get(domainId) : NEUTRAL);
  }
}
