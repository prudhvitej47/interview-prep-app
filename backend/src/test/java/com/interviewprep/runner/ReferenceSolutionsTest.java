package com.interviewprep.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every coding unit's reference solution, from a real content bundle, through the same harness and sandbox
 * learners use: the guard that the runner judges code the way the content repository's check_coding.py does.
 * Opt-in (it needs Docker and a bundle, and takes a few minutes):
 *
 * <pre>./mvnw test -Dtest=ReferenceSolutionsTest -DcontentBundle=../../interview-prep-content/dist/content.json</pre>
 */
class ReferenceSolutionsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern SOLUTION = Pattern.compile("^## Solution[^\\n]*\\n(.*?)(?=^## |\\z)", Pattern.MULTILINE | Pattern.DOTALL);
  private static final Pattern JAVA = Pattern.compile("```java\\n(.*?)```", Pattern.DOTALL);

  @Test
  void everyReferenceSolutionPasses() throws Exception {
    String bundle = System.getProperty("contentBundle");
    assumeTrue(bundle != null && !bundle.isBlank(), "pass -DcontentBundle=<path to content.json> to run");
    JsonNode content = JSON.readTree(Files.readString(Path.of(bundle)));
    RunService service = new RunService(new DockerSandbox("docker", DockerSandbox.DEFAULT_IMAGE, null));
    List<String> problems = new ArrayList<>();
    int checked = 0;
    for (JsonNode unit : content.path("units")) {
      if (!unit.path("type").asString().equals("coding") || !unit.path("tests").has("signature")) {
        continue;
      }
      String id = unit.path("id").asString();
      Matcher section = SOLUTION.matcher(unit.path("body").path("markdown").asString());
      Matcher code = section.find() ? JAVA.matcher(section.group(1)) : null;
      if (code == null || !code.find()) {
        problems.add(id + ": no ```java block under ## Solution");
        continue;
      }
      JsonNode tests = unit.path("tests");
      List<Wire.Param> params = new ArrayList<>();
      tests.path("signature").path("params").forEach(p -> params.add(new Wire.Param(p.path("name").asString(), p.path("type").asString())));
      List<Wire.CaseIn> cases = new ArrayList<>();
      int i = 0;
      for (JsonNode c : tests.path("cases")) {
        cases.add(new Wire.CaseIn("case-" + i++, c.path("args"), c.path("expected_value"), true));
      }
      Wire.RunResponse r = service.run(new Wire.RunRequest(code.group(1),
          new Wire.Signature(tests.path("signature").path("method").asString(), params,
              tests.path("signature").path("returns").asString()),
          tests.path("compare").asString("exact"), cases));
      checked++;
      long failed = r.cases().stream().filter(c -> !c.verdict().equals("pass")).count();
      if (!r.status().equals("finished") || failed > 0 || r.cases().size() != cases.size()) {
        problems.add(id + ": " + r.status() + ", " + failed + " case(s) not passing"
            + (r.compileErrors() == null ? "" : "\n" + r.compileErrors()));
      }
    }
    System.out.println("reference solutions checked: " + checked + ", problems: " + problems.size());
    assertThat(problems).as(String.join("\n", problems)).isEmpty();
    assertThat(checked).isGreaterThan(0);
  }
}
