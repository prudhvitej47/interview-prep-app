package com.interviewprep.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Smoke mode: one tiny run through the service, as a deployment check uses it. */
class RunnerMainTest {

  @Test
  void smokeReportsStatusAndVerdict() {
    Sandbox passing = new Sandbox() {
      @Override public Result run(Map<String, String> files) {
        return new Result(0, "@@START\n@@CASE {\"case\":\"smoke\",\"verdict\":\"pass\",\"got\":\"2\",\"error\":null,\"ms\":1}\n"
            + "@@OUTPUT \"\"\n@@END\n", false, false, 900);
      }
      @Override public boolean healthy() { return true; }
    };
    assertThat(RunnerMain.smoke(new RunService(passing))).isEqualTo("finished pass");
  }
}
