package com.interviewprep.planner;

import static com.interviewprep.progress.ProgressQueries.STUDY_ZONE;

import com.interviewprep.curriculum.DomainCatalog;
import com.interviewprep.curriculum.DomainSummary;
import com.interviewprep.learner.LearnerPrincipal;
import com.interviewprep.planner.UnitStages.StageCounts;
import com.interviewprep.planner.UnitStages.StagedUnit;
import com.interviewprep.progress.Stage.Standing;
import com.interviewprep.projects.ProjectPlanning;
import com.interviewprep.projects.ProjectPlanning.QuestionStanding;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The progress page: where the signed-in learner stands with each unit and each question about their
 * own projects, by stage (not started, learned, review due, solid; see {@link
 * com.interviewprep.progress.Stage}). Derived from attempts alone; nothing here is stored.
 *
 * <p>It lives in the planner because it reads the curriculum, the learner's "not for me" marks, their
 * attempts and their projects together, and the planner is already the module that may use all four.
 */
@RestController
class ProgressController {

  private final UnitStages stages;
  private final DomainCatalog domains;
  private final ProjectPlanning projects;

  ProgressController(UnitStages stages, DomainCatalog domains, ProjectPlanning projects) {
    this.stages = stages;
    this.domains = domains;
    this.projects = projects;
  }

  /** An area and its units by stage, leaving out the learner's "not for me" units. */
  record Area(String domainId, String name, StageCounts stages) {}

  /**
   * A unit and where the learner stands with it. Units marked "not for me" are listed too, flagged,
   * so a topic page can say so; the areas' counts and the progress page leave them out.
   */
  record UnitRow(String unitId, String title, String type, String topicId, String topicName, String domainId,
      String stage, int reviews, LocalDate dueOn, boolean notForMe) {}

  record QuestionRow(long questionId, String rung, String prompt, String stage, int reviews, LocalDate dueOn) {}

  record ProjectRow(long projectId, String name, StageCounts stages, List<QuestionRow> questions) {}

  /** {@code notForMe}: how many units the areas leave out because the learner kept them out of plans. */
  record ProgressView(LocalDate today, List<Area> areas, List<UnitRow> units, int notForMe,
      List<ProjectRow> projects) {}

  @GetMapping("/api/progress")
  ProgressView progress(@AuthenticationPrincipal LearnerPrincipal me) {
    LocalDate today = LocalDate.now(STUDY_ZONE);
    List<StagedUnit> staged = stages.of(me);
    List<DomainSummary> all = domains.all();
    Map<String, Integer> order = new HashMap<>();
    all.forEach(d -> order.put(d.id(), order.size()));

    List<Area> areas = new ArrayList<>();
    for (DomainSummary d : all) {
      List<Standing> in = staged.stream().filter(s -> !s.notForMe() && s.unit().domainId().equals(d.id()))
          .map(StagedUnit::standing).toList();
      if (!in.isEmpty()) {
        areas.add(new Area(d.id(), d.name(), StageCounts.of(in)));
      }
    }

    List<UnitRow> units = staged.stream()
        .sorted(Comparator.comparing((StagedUnit s) -> order.getOrDefault(s.unit().domainId(), Integer.MAX_VALUE))
            .thenComparing(s -> s.unit().topicName()).thenComparing(s -> s.unit().title()))
        .map(s -> new UnitRow(s.unit().id(), s.unit().title(), s.unit().type(), s.unit().topicId(),
            s.unit().topicName(), s.unit().domainId(), s.standing().stage().id(), s.standing().reviews(),
            s.standing().dueOn(), s.notForMe()))
        .toList();
    int notForMe = (int) staged.stream().filter(StagedUnit::notForMe).count();

    Map<Long, List<QuestionStanding>> byProject = new LinkedHashMap<>();
    projects.standings(me.id(), today)
        .forEach(q -> byProject.computeIfAbsent(q.projectId(), k -> new ArrayList<>()).add(q));
    List<ProjectRow> projectRows = byProject.values().stream()
        .map(qs -> new ProjectRow(qs.getFirst().projectId(), qs.getFirst().projectName(),
            StageCounts.of(qs.stream().map(QuestionStanding::standing).toList()),
            qs.stream().map(q -> new QuestionRow(q.questionId(), q.rung(), q.prompt(), q.standing().stage().id(),
                q.standing().reviews(), q.standing().dueOn())).toList()))
        .toList();
    return new ProgressView(today, areas, units, notForMe, projectRows);
  }
}
