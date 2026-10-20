package com.interviewprep.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The runner stays liftable (runner design §3): it uses only the JDK and Jackson, and nothing in the app
 * imports it. The app will talk to it over HTTP only.
 */
class RunnerIsolationTest {

  private static final Path MAIN = Path.of("src/main/java/com/interviewprep");
  private static final Pattern IMPORT = Pattern.compile("^import (?:static )?([\\w.]+)", Pattern.MULTILINE);

  @Test
  void theRunnerImportsOnlyTheJdkAndJackson() throws IOException {
    for (Path file : javaFiles(MAIN.resolve("runner"))) {
      Matcher m = IMPORT.matcher(Files.readString(file));
      while (m.find()) {
        assertThat(m.group(1)).as(file + " imports " + m.group(1))
            .matches("(java|javax\\.annotation|tools\\.jackson|com\\.sun\\.net\\.httpserver|com\\.interviewprep\\.runner)\\..*");
      }
    }
  }

  @Test
  void nothingElseImportsTheRunner() throws IOException {
    for (Path file : javaFiles(MAIN)) {
      if (file.startsWith(MAIN.resolve("runner"))) continue;
      assertThat(Files.readString(file)).as(file.toString()).doesNotContain("com.interviewprep.runner");
    }
  }

  private static List<Path> javaFiles(Path root) throws IOException {
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(p -> p.toString().endsWith(".java")).toList();
    }
  }
}
