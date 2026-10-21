package com.interviewprep.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The runner against real Docker with the exact sandbox flags and hostile learner code. Every run must come
 * back with the right status within 15 s and leave no container behind. Needs Docker (CI runners have it).
 */
class DockerSandboxTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final DockerSandbox SANDBOX = new DockerSandbox("docker", DockerSandbox.DEFAULT_IMAGE, null);
  private static final RunService SERVICE = new RunService(SANDBOX);

  @BeforeAll
  static void dockerAndImage() throws Exception {
    Process info = new ProcessBuilder("docker", "info").redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    assumeTrue(info.waitFor(20, TimeUnit.SECONDS) && info.exitValue() == 0, "Docker is not running");
    Process pull = new ProcessBuilder("docker", "pull", DockerSandbox.DEFAULT_IMAGE).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    assertThat(pull.waitFor(5, TimeUnit.MINUTES)).isTrue();
    assertThat(SANDBOX.healthy()).isTrue();
  }

  private static Wire.RunResponse run(String source, String returns, boolean hidden, String expected) {
    Wire.RunRequest request = new Wire.RunRequest(source,
        new Wire.Signature("f", List.of(new Wire.Param("x", "int")), returns), "exact",
        List.of(new Wire.CaseIn("first", JSON.readTree("{\"x\": 1}"), JSON.readTree(expected), !hidden)));
    long start = System.nanoTime();
    Wire.RunResponse response = SERVICE.run(request);
    assertThat((System.nanoTime() - start) / 1_000_000).as("run time").isLessThan(15_000);
    return response;
  }

  private static long leftoverContainers() throws Exception {
    Process p = new ProcessBuilder("docker", "ps", "-aq", "--filter", "label=interview-prep.run=1").start();
    String out = new String(p.getInputStream().readAllBytes());
    p.waitFor();
    return out.lines().filter(l -> !l.isBlank()).count();
  }

  @Test
  void rightAndWrongAnswers() {
    Wire.RunResponse right = run("class Solution { int f(int x) { return x + 1; } }", "int", false, "2");
    assertThat(right.status()).isEqualTo("finished");
    assertThat(right.cases().get(0).verdict()).isEqualTo("pass");
    Wire.RunResponse wrong = run("class Solution { int f(int x) { return x; } }", "int", false, "2");
    assertThat(wrong.cases().get(0).got()).isEqualTo("1");
  }

  @Test
  void compileErrorAndSignatureMismatch() {
    Wire.RunResponse compile = run("class Solution {\n  int f(int x) {\n    return x\n  }\n}\n", "int", false, "1");
    assertThat(compile.status()).isEqualTo("compile-error");
    assertThat(compile.compileErrors()).contains("Solution.java:3").doesNotContain("/work/");
    Wire.RunResponse mismatch = run("class Solution { int g(int x) { return x; } }", "int", false, "1");
    assertThat(mismatch.status()).isEqualTo("signature-mismatch");
    assertThat(mismatch.compileErrors()).isEqualTo("Solution needs a method: int f(int x)");
  }

  @Test
  void anEndlessLoopIsATimeout() {
    assertThat(run("class Solution { int f(int x) { while (true) { } } }", "int", false, "1").status())
        .isEqualTo("timeout");
  }

  @Test
  void aMemoryHogRunsOutOfMemory() {
    String hog = "import java.util.*;\nclass Solution { int f(int x) { List<byte[]> l = new ArrayList<>();"
        + " while (true) l.add(new byte[1 << 20]); } }";
    assertThat(run(hog, "int", false, "1").status()).isEqualTo("oom");
  }

  @Test
  void aThreadBombIsStoppedByTheProcessLimit() {
    String bomb = "class Solution { int f(int x) { while (true) { Thread t = new Thread(() -> {"
        + " try { Thread.sleep(60000); } catch (InterruptedException e) { } }); t.setDaemon(true); t.start(); } } }";
    Wire.RunResponse r = run(bomb, "int", false, "1");
    assertThat(r.status()).isIn("finished", "crashed");
    if (r.status().equals("finished")) {
      assertThat(r.cases().get(0).error()).contains("OutOfMemoryError");
    }
  }

  @Test
  void noNetworkAndNoWritingOutsideTheScratchSpace() {
    String socket = "class Solution { String f(int x) { try (var s = new java.net.Socket()) {"
        + " s.connect(new java.net.InetSocketAddress(\"1.1.1.1\", 53), 1000); return \"open\"; }"
        + " catch (Exception e) { return \"blocked\"; } } }";
    assertThat(run(socket, "String", false, "\"blocked\"").cases().get(0).verdict()).isEqualTo("pass");
    String write = "class Solution { String f(int x) { try { java.nio.file.Files.writeString("
        + "java.nio.file.Path.of(\"/evil\"), \"x\"); return \"written\"; } catch (Exception e) { return \"blocked\"; } } }";
    assertThat(run(write, "String", false, "\"blocked\"").cases().get(0).verdict()).isEqualTo("pass");
  }

  @Test
  void floodingTheRealStdoutIsCutAndExitingEarlyIsACrash() {
    String flood = "class Solution { int f(int x) { try { var out = new java.io.FileOutputStream(java.io.FileDescriptor.out);"
        + " byte[] b = new byte[8192]; java.util.Arrays.fill(b, (byte) 'x'); while (true) out.write(b); }"
        + " catch (Exception e) { return 0; } } }";
    assertThat(run(flood, "int", false, "1").status()).isEqualTo("crashed");
    assertThat(run("class Solution { int f(int x) { System.exit(0); return x; } }", "int", false, "1").status())
        .isEqualTo("crashed");
  }

  @Test
  void aHiddenCaseLeavesNothingButItsVerdict() throws Exception {
    Wire.RunResponse r = run("class Solution { int f(int x) { System.out.println(\"secret 4242\"); return 7; } }",
        "int", true, "2");
    assertThat(r.cases().get(0).verdict()).isEqualTo("fail");
    assertThat(JSON.writeValueAsString(r)).doesNotContain("7").doesNotContain("secret").doesNotContain("4242");
    assertThat(leftoverContainers()).isZero();
  }

  @Test
  void aCompileErrorNeverEchoesAHiddenCase() {
    // A typo plus a renamed method: javac also reports Main.java, whose lines hold the case literals.
    Wire.RunRequest request = new Wire.RunRequest("class Solution {\n  int g(int x) { return y; }\n}\n",
        new Wire.Signature("f", List.of(new Wire.Param("x", "int")), "int"), "exact",
        List.of(new Wire.CaseIn("hidden", JSON.readTree("{\"x\": 424242}"), JSON.readTree("777777"), false)));
    Wire.RunResponse r = SERVICE.run(request);
    assertThat(r.status()).isEqualTo("compile-error");
    assertThat(r.compileErrors()).contains("Solution.java:2").doesNotContain("424242").doesNotContain("777777")
        .doesNotContain("Main.java");
  }

  @Test
  void aHungImageCheckIsUnhealthyAndLeavesNoProcessBehind(@TempDir Path dir) throws Exception {
    Path hang = dir.resolve("docker");
    Files.writeString(hang, "#!/bin/sh\nexec sleep 60\n");
    hang.toFile().setExecutable(true);
    assertThat(new DockerSandbox(hang.toString(), DockerSandbox.DEFAULT_IMAGE, null).healthy()).isFalse();
    Thread.sleep(300);
    assertThat(ProcessHandle.current().children().filter(ProcessHandle::isAlive)
        .filter(h -> h.info().command().map(c -> c.endsWith("sleep")).orElse(false))).isEmpty();
  }
}
