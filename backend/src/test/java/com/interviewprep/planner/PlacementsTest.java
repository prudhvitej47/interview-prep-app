package com.interviewprep.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PlacementsTest {

  private static final String CHANGELOG = """
      # Job scheduler from a new report

      ## What changed
      - New units: ...

      ## Suggested placement
      | Unit | Tripti | Prudhvi |
      | --- | --- | --- |
      | `hld.modern.job-scheduler` | now | next week |
      | ds.locks.leases | End of track | later |
      | dsa.graphs.topo | Next week, after locks | now (interview soon) |
      """;

  @Test
  void eachLearnerGetsTheirOwnColumnBySlugOrName() {
    assertThat(Placements.suggested(CHANGELOG, "tripti", "Tripti")).isEqualTo(Map.of(
        "hld.modern.job-scheduler", "now", "ds.locks.leases", "end-of-track", "dsa.graphs.topo", "next-week"));
    assertThat(Placements.suggested(CHANGELOG, "pj", "Prudhvi")).isEqualTo(Map.of(
        "hld.modern.job-scheduler", "next-week", "ds.locks.leases", "end-of-track", "dsa.graphs.topo", "now"));
  }

  @Test
  void anythingUnreadableSuggestsNothing() {
    assertThat(Placements.suggested(null, "tripti", "Tripti")).isEmpty();
    assertThat(Placements.suggested("# No table here", "tripti", "Tripti")).isEmpty();
    assertThat(Placements.suggested(CHANGELOG, "someone", "Someone")).isEmpty();
  }

  @Test
  void unclearWordsMeanNextWeek() {
    assertThat(Placements.choice("soon-ish")).isEqualTo("next-week");
    assertThat(Placements.choice(" Now ")).isEqualTo("now");
  }
}
