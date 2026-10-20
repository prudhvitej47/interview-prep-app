package com.interviewprep.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Outcome classification and hidden-case hiding, against canned sandbox output. */
class RunServiceTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static Wire.RunRequest request(boolean secondHidden) {
    return new Wire.RunRequest("class Solution { int f(int x) { return x; } }",
        new Wire.Signature("f", List.of(new Wire.Param("x", "int")), "int"), "exact",
        List.of(new Wire.CaseIn("a", JSON.readTree("{\"x\": 1}"), JSON.readTree("1"), true),
            new Wire.CaseIn("b", JSON.readTree("{\"x\": 2}"), JSON.readTree("2"), !secondHidden)));
  }

  private static RunService serviceReturning(int exit, String output, boolean timedOut, boolean cut) {
    return new RunService(new Sandbox() {
      @Override public Result run(Map<String, String> files) { return new Result(exit, output, timedOut, cut, 1500); }
      @Override public boolean healthy() { return true; }
    });
  }

  private static final String BOTH_PASS = """
      @@START
      @@CASE {"case":"a","verdict":"pass","got":"1","error":null,"ms":3}
      @@CASE {"case":"b","verdict":"fail","got":"7","error":null,"ms":4}
      @@OUTPUT "printed by the learner"
      @@END
      """;

  @Test
  void aFinishedRunShowsVisibleDetailAndPrintedOutput() {
    Wire.RunResponse r = serviceReturning(0, BOTH_PASS, false, false).run(request(false));
    assertThat(r.status()).isEqualTo("finished");
    assertThat(r.cases()).extracting(Wire.CaseOut::got).containsExactly("1", "7");
    assertThat(r.output()).isEqualTo("printed by the learner");
  }

  @Test
  void aHiddenCaseKeepsOnlyItsVerdictAndTheRunSendsNoOutput() {
    Wire.RunResponse r = serviceReturning(0, BOTH_PASS, false, false).run(request(true));
    Wire.CaseOut hidden = r.cases().get(1);
    assertThat(hidden.verdict()).isEqualTo("fail");
    assertThat(hidden.got()).isNull();
    assertThat(hidden.error()).isNull();
    assertThat(hidden.ms()).isNull();
    assertThat(r.output()).isNull();
    assertThat(JSON.writeValueAsString(r)).doesNotContain("\"7\"").doesNotContain("printed by the learner");
  }

  @Test
  void compileErrorsAreTheLearnersAndSignatureMismatchesAreNamed() {
    String compile = "/work/Solution.java:3: error: ';' expected\n    return x\n            ^\n1 error\nerror: compilation failed\n";
    Wire.RunResponse c = serviceReturning(1, compile, false, false).run(request(false));
    assertThat(c.status()).isEqualTo("compile-error");
    assertThat(c.compileErrors()).startsWith("Solution.java:3: error: ';' expected").doesNotContain("/work/");
    assertThat(c.cases()).isEmpty();

    String mismatch = "/work/Main.java:40: error: method f in class Solution cannot be applied to given types;\n1 error\n";
    Wire.RunResponse m = serviceReturning(1, mismatch, false, false).run(request(false));
    assertThat(m.status()).isEqualTo("signature-mismatch");
    assertThat(m.compileErrors()).isEqualTo("Solution needs a method: int f(int x)");
  }

  @Test
  void timeoutsOutOfMemoryAndCrashes() {
    String harnessTimeout = "@@START\n@@CASE {\"case\":\"a\",\"verdict\":\"timeout\",\"got\":null,\"error\":null,\"ms\":2000}\n"
        + "@@OUTPUT \"\"\n@@TIMEOUT \"a\"\n";
    Wire.RunResponse t = serviceReturning(3, harnessTimeout, false, false).run(request(false));
    assertThat(t.status()).isEqualTo("timeout");
    assertThat(t.cases()).extracting(Wire.CaseOut::verdict).containsExactly("timeout");

    assertThat(serviceReturning(-1, "@@START\n", true, false).run(request(false)).status()).isEqualTo("timeout");
    assertThat(serviceReturning(137, "@@START\n", false, false).run(request(false)).status()).isEqualTo("oom");
    String heap = "@@START\n@@CASE {\"case\":\"a\",\"verdict\":\"error\",\"got\":null,"
        + "\"error\":\"OutOfMemoryError: Java heap space\",\"ms\":900}\n";
    assertThat(serviceReturning(0, heap + "@@CASE {\"case\":\"b\",\"verdict\":\"pass\",\"got\":\"2\",\"error\":null,\"ms\":1}\n"
        + "@@OUTPUT \"\"\n@@END\n", false, false).run(request(false)).status()).isEqualTo("oom");
    assertThat(serviceReturning(0, "@@START\n", false, false).run(request(false)).status()).isEqualTo("crashed");
    assertThat(serviceReturning(0, "x".repeat(100), false, true).run(request(false)).status()).isEqualTo("crashed");
    assertThat(serviceReturning(1, "Error: could not start the JVM\n", false, false).run(request(false)).status())
        .isEqualTo("crashed");
  }

  @Test
  void anEndWithMissingCaseLinesIsACrashNotAFinish() {
    String swallowed = "@@START\n@@CASE {\"case\":\"a\",\"verdict\":\"pass\",\"got\":\"1\",\"error\":null,\"ms\":1}\n@@OUTPUT \"\"\n@@END\n";
    assertThat(serviceReturning(0, swallowed, false, false).run(request(false)).status()).isEqualTo("crashed");
  }

  @Test
  void requestsItWillNotRunAreRefused() {
    RunService service = serviceReturning(0, BOTH_PASS, false, false);
    Wire.RunRequest ok = request(false);
    assertThatThrownBy(() -> service.run(new Wire.RunRequest("x".repeat(RunService.SOURCE_LIMIT_BYTES + 1),
        ok.signature(), "exact", ok.cases()))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32 KB");
    assertThatThrownBy(() -> service.run(new Wire.RunRequest(ok.source(),
        new Wire.Signature("f", List.of(new Wire.Param("x", "short")), "int"), "exact", ok.cases())))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("short");
    assertThatThrownBy(() -> service.run(new Wire.RunRequest(ok.source(), ok.signature(), "exact", List.of())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.run(new Wire.RunRequest(ok.source(), ok.signature(), "exact",
        List.of(ok.cases().get(0), ok.cases().get(0))))).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("twice");
    assertThatThrownBy(() -> service.run(new Wire.RunRequest(ok.source(), ok.signature(), "sorted", ok.cases())))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
