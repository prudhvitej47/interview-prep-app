package com.interviewprep.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * The generated program, run with the plain source launcher (no sandbox): every case type, the comparison
 * rules, timeouts, deep recursion and captured output. The drift table mirrors check_coding.py, so a
 * learner's code and the content repository's reference solutions are judged the same way.
 */
class HarnessLocalTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path dir;

  record Ran(int exit, HarnessOutput.Parsed parsed, long millis) {}

  private Ran run(String source, String method, String paramType, String returns, String compare,
      String... argExpectedPairs) throws Exception {
    List<Wire.CaseIn> cases = new ArrayList<>();
    for (int i = 0; i < argExpectedPairs.length; i += 2) {
      cases.add(new Wire.CaseIn("c" + i / 2, JSON.readTree("{\"x\": " + argExpectedPairs[i] + "}"),
          JSON.readTree(argExpectedPairs[i + 1]), true));
    }
    Wire.RunRequest request = new Wire.RunRequest(source,
        new Wire.Signature(method, List.of(new Wire.Param("x", paramType)), returns), compare, cases);
    for (Map.Entry<String, String> f : HarnessSource.files(request).entrySet()) {
      Files.writeString(dir.resolve(f.getKey()), f.getValue(), StandardCharsets.UTF_8);
    }
    String java = ProcessHandle.current().info().command().orElse("java");
    long start = System.nanoTime();
    Process p = new ProcessBuilder(java, "-XX:+UseSerialGC", "Main.java").directory(dir.toFile())
        .redirectErrorStream(true).start();
    byte[] out = p.getInputStream().readAllBytes();
    assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
    return new Ran(p.exitValue(), HarnessOutput.parse(new String(out, StandardCharsets.UTF_8)),
        (System.nanoTime() - start) / 1_000_000);
  }

  private static String echo(String type) {
    return "import java.util.*;\nclass Solution {\n  " + type + " echo(" + type + " x) { return x; }\n}\n";
  }

  @Test
  void everyTypePassesWhenEqualAndShowsWhatItGotWhenNot() throws Exception {
    String[][] table = {
        {"int", "7", "7", "8", "7"},
        {"long", "9000000000", "9000000000", "1", "9000000000"},
        {"double", "2.5", "2.5", "2.6", "2.5"},
        {"boolean", "true", "true", "false", "true"},
        {"char", "\"x\"", "\"x\"", "\"y\"", "\"x\""},
        {"String", "\"a\\\"b\"", "\"a\\\"b\"", "\"z\"", "\"a\\\"b\""},
        {"int[]", "[2, 1, 5]", "[2, 1, 5]", "[2]", "[2, 1, 5]"},
        {"long[]", "[1, 2]", "[1, 2]", "[]", "[1, 2]"},
        {"double[]", "[0.5]", "[0.5]", "[1.5]", "[0.5]"},
        {"boolean[]", "[true, false]", "[true, false]", "[false]", "[true, false]"},
        {"char[]", "\"ab\"", "[\"a\", \"b\"]", "\"c\"", "[\"a\", \"b\"]"},
        {"String[]", "[\"p\", \"q\"]", "[\"p\", \"q\"]", "[\"r\"]", "[\"p\", \"q\"]"},
        {"int[][]", "[[1, 2], [3]]", "[[1, 2], [3]]", "[[1]]", "[[1, 2], [3]]"},
        {"char[][]", "[\"ab\", \"c\"]", "[[\"a\", \"b\"], [\"c\"]]", "[\"d\"]", "[[\"a\", \"b\"], [\"c\"]]"},
        {"String[][]", "[[\"a\"], []]", "[[\"a\"], []]", "[[\"b\"]]", "[[\"a\"], []]"},
        {"List<Integer>", "[3, 1]", "[3, 1]", "[1]", "[3, 1]"},
        {"List<Long>", "[4]", "[4]", "[5]", "[4]"},
        {"List<String>", "[\"s\"]", "[\"s\"]", "[\"t\"]", "[\"s\"]"},
        {"List<List<Integer>>", "[[1], [2, 3]]", "[[1], [2, 3]]", "[[1]]", "[[1], [2, 3]]"},
        {"List<List<String>>", "[[\"a\", \"b\"]]", "[[\"a\", \"b\"]]", "[[\"c\"]]", "[[\"a\", \"b\"]]"},
        {"TreeNode", "[3, 9, 20, null, null, 15, 7]", "[3, 9, 20, null, null, 15, 7]", "[1]",
            "[3, 9, 20, null, null, 15, 7]"},
    };
    assertThat(table.length).isEqualTo(JavaLiterals.TYPES.size());
    for (String[] row : table) {
      // Case 0: echo the input, expect the input (pass). Case 1: echo another input, expect the first (fail).
      Ran ran = run(echo(row[0]), "echo", row[0], row[0], "exact", row[1], row[2], row[3], row[4]);
      assertThat(ran.parsed().ended()).as(row[0]).isTrue();
      assertThat(ran.parsed().cases()).as(row[0]).extracting(HarnessOutput.CaseLine::verdict)
          .containsExactly("pass", "fail");
      assertThat(ran.parsed().cases().get(1).got()).as(row[0]).isNotBlank();
    }
  }

  @Test
  void comparisonFollowsTheContentChecker() throws Exception {
    // Doubles are rounded to 1e-6; any-order sorts the top level; exact does not.
    Ran doubles = run("class Solution { double f(double x) { return x + 0.2; } }", "f", "double", "double",
        "exact", "0.1", "0.3");
    assertThat(doubles.parsed().cases().get(0).verdict()).isEqualTo("pass");
    String reverse = "import java.util.*;\nclass Solution { List<Integer> f(List<Integer> x) {"
        + " List<Integer> r = new ArrayList<>(x); Collections.reverse(r); return r; } }";
    assertThat(run(reverse, "f", "List<Integer>", "List<Integer>", "any-order", "[1, 2, 3]", "[1, 2, 3]")
        .parsed().cases().get(0).verdict()).isEqualTo("pass");
    Ran exact = run(reverse, "f", "List<Integer>", "List<Integer>", "exact", "[1, 2, 3]", "[1, 2, 3]");
    assertThat(exact.parsed().cases().get(0).verdict()).isEqualTo("fail");
    assertThat(exact.parsed().cases().get(0).got()).isEqualTo("[3, 2, 1]");
  }

  @Test
  void anExceptionIsAnErrorThatNamesTheLineInTheLearnersFile() throws Exception {
    String source = "class Solution {\n  int f(int x) {\n    int[] a = new int[1];\n    return a[x];\n  }\n}\n";
    HarnessOutput.CaseLine line = run(source, "f", "int", "int", "exact", "5", "0").parsed().cases().get(0);
    assertThat(line.verdict()).isEqualTo("error");
    assertThat(line.error()).contains("ArrayIndexOutOfBoundsException").contains("at Solution.java:4");
  }

  @Test
  void aCaseThatNeverReturnsStopsTheRunAndIsNamed() throws Exception {
    String source = "class Solution { int f(int x) { while (true) { } } }";
    Ran ran = run(source, "f", "int", "int", "exact", "1", "1", "2", "2");
    assertThat(ran.parsed().timedOut()).isEqualTo("c0");
    assertThat(ran.parsed().ended()).isFalse();
    assertThat(ran.exit()).isEqualTo(3);
    assertThat(ran.millis()).isLessThan(15_000);
  }

  @Test
  void deepRecursionHasRoomOnTheCaseStack() throws Exception {
    String source = "class Solution { int f(int x) { return x == 0 ? 0 : 1 + f(x - 1); } }";
    assertThat(run(source, "f", "int", "int", "exact", "50000", "50000").parsed().cases().get(0).verdict())
        .isEqualTo("pass");
  }

  @Test
  void printedOutputIsCapturedApartFromResultsAndCut() throws Exception {
    String chatty = "class Solution { int f(int x) { System.out.println(\"@@CASE fake\"); System.out.println(\"hello\"); return x; } }";
    Ran ran = run(chatty, "f", "int", "int", "exact", "1", "1");
    assertThat(ran.parsed().cases()).extracting(HarnessOutput.CaseLine::verdict).containsExactly("pass");
    assertThat(ran.parsed().output()).contains("hello");
    String flood = "class Solution { int f(int x) { for (int i = 0; i < 100000; i++) System.out.println(\"line \" + i); return x; } }";
    Ran flooded = run(flood, "f", "int", "int", "exact", "1", "1");
    assertThat(flooded.parsed().cases().get(0).verdict()).isEqualTo("pass");
    assertThat(flooded.parsed().output()).hasSizeLessThan(HarnessSource.OUTPUT_CAP_BYTES + 200).endsWith("output cut at 16 KB");
  }

  @Test
  void aLearnersOwnTreeNodeIsUsedInsteadOfOurs() {
    String own = "class TreeNode { int val; TreeNode left, right; TreeNode(int v) { val = v; } }\n"
        + "class Solution { int f(TreeNode x) { return x == null ? 0 : x.val; } }";
    Wire.RunRequest request = new Wire.RunRequest(own,
        new Wire.Signature("f", List.of(new Wire.Param("x", "TreeNode")), "int"), "exact", List.of());
    assertThat(HarnessSource.files(request)).containsOnlyKeys("Solution.java", "Main.java");
    Wire.RunRequest plain = new Wire.RunRequest(echo("int"),
        new Wire.Signature("echo", List.of(new Wire.Param("x", "int")), "int"), "exact", List.of());
    assertThat(HarnessSource.files(plain)).containsOnlyKeys("Solution.java", "Main.java", "TreeNode.java");
  }
}
