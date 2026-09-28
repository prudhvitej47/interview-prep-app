package com.interviewprep.planner;

import static com.interviewprep.progress.ProgressQueries.STUDY_ZONE;

import com.interviewprep.curriculum.CurriculumQueries;
import com.interviewprep.curriculum.CurriculumQueries.PlannableUnit;
import com.interviewprep.curriculum.CurriculumQueries.TopicPlace;
import com.interviewprep.learner.LearnerPrincipal;
import com.interviewprep.learner.LearnerProfile;
import com.interviewprep.learner.LearnerProfile.NotForMe;
import com.interviewprep.progress.ProgressQueries;
import com.interviewprep.progress.ProgressQueries.Attempt;
import com.interviewprep.progress.Stage;
import com.interviewprep.progress.Stage.Standing;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Every unit a learner can see that is still in the curriculum, with where they stand with it
 * ({@link Stage}) and whether they have kept it out of their plans. The home page's coverage and the
 * progress page both count from this, so the two always agree.
 */
@Component
class UnitStages {

  /** A unit and the learner's standing with it. {@code notForMe}: its own mark or a topic's above it. */
  record StagedUnit(PlannableUnit unit, Standing standing, boolean notForMe) {}

  /** How many of a set of units, or questions, are at each stage. */
  record StageCounts(int notStarted, int learned, int reviewDue, int solid) {
    static StageCounts of(List<Standing> standings) {
      int[] c = new int[4];
      standings.forEach(s -> c[switch (s.stage()) {
        case NOT_STARTED -> 0;
        case LEARNED -> 1;
        case REVIEW_DUE -> 2;
        case SOLID -> 3;
      }]++);
      return new StageCounts(c[0], c[1], c[2], c[3]);
    }

    int total() {
      return notStarted + learned + reviewDue + solid;
    }
  }

  private final CurriculumQueries curriculum;
  private final ProgressQueries progress;
  private final LearnerProfile profile;

  UnitStages(CurriculumQueries curriculum, ProgressQueries progress, LearnerProfile profile) {
    this.curriculum = curriculum;
    this.progress = progress;
    this.profile = profile;
  }

  /** In the planner's unit order; retired units and units the learner cannot see are never here. */
  List<StagedUnit> of(LearnerPrincipal me) {
    List<PlannableUnit> units = curriculum.plannable(me.slug());
    Map<String, TopicPlace> topics = curriculum.topicPlaces();
    NotForMe notForMe = profile.notForMe(me.id());
    Map<String, List<Attempt>> byUnit = new LinkedHashMap<>();
    progress.attempts(me.id()).forEach(a -> byUnit.computeIfAbsent(a.unitId(), k -> new ArrayList<>()).add(a));
    LocalDate today = LocalDate.now(STUDY_ZONE);
    return units.stream()
        .map(u -> new StagedUnit(u, ProgressQueries.standing(byUnit.getOrDefault(u.id(), List.of()), today),
            PlanController.excluded(u, notForMe, topics)))
        .toList();
  }
}
