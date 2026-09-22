package com.interviewprep.gamification;

import com.interviewprep.curriculum.CurriculumQueries;
import com.interviewprep.learner.LearnerPrincipal;
import com.interviewprep.planner.PlanQueries;
import com.interviewprep.progress.ProgressQueries;
import com.interviewprep.progress.ProgressQueries.Attempt;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RewardsController {

  private final ProgressQueries progress;
  private final PlanQueries plans;
  private final CurriculumQueries curriculum;

  RewardsController(ProgressQueries progress, PlanQueries plans, CurriculumQueries curriculum) {
    this.progress = progress;
    this.plans = plans;
    this.curriculum = curriculum;
  }

  @GetMapping("/api/rewards")
  Rewards.Summary rewards(@AuthenticationPrincipal LearnerPrincipal me) {
    List<Attempt> attempts = progress.attempts(me.id());
    return Rewards.of(attempts,
        curriculum.typesOf(attempts.stream().map(Attempt::unitId).distinct().toList()),
        plans.results(me.id()), plans.breaks(me.id()), PlanQueries.thisMonday());
  }
}
