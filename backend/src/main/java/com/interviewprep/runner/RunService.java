package com.interviewprep.runner;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One run, start to answer: refuse what it will not run, generate the files, run the sandbox, read the
 * harness's lines, decide the outcome (runner design §4), and drop every detail of hidden cases (§5)
 * before anything leaves the runner.
 */
final class RunService {

  static final int SOURCE_LIMIT_BYTES = 32 * 1024;
  static final int MAX_CASES = 500;
  static final int DIAGNOSTICS_LIMIT = 8 * 1024;

  private static final Logger LOG = System.getLogger(RunService.class.getName());
  private static final Pattern LEARNER_DIAGNOSTIC = Pattern.compile("^(/work/)?(Solution|TreeNode)\\.java:\\d+: error", Pattern.MULTILINE);
  private static final Pattern BLOCK_HEADER = Pattern.compile("^(/work/)?(\\w+)\\.java:\\d+: (error|warning)");
  private static final Pattern SUMMARY = Pattern.compile("^(\\d+ (errors?|warnings?)|error: compilation failed)$");
  private static final Pattern NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
  private static final Pattern HARNESS_DIAGNOSTIC = Pattern.compile("^(/work/)?Main\\.java:\\d+: error", Pattern.MULTILINE);

  private final Sandbox sandbox;

  RunService(Sandbox sandbox) {
    this.sandbox = sandbox;
  }

  Wire.RunResponse run(Wire.RunRequest request) {
    validate(request);
    Map<String, String> files = HarnessSource.files(request);
    Sandbox.Result result = sandbox.run(files);
    Wire.RunResponse response = classify(request, result);
    LOG.log(Level.INFO, "run status={0} cases={1} hidden={2} ms={3} exit={4}", response.status(),
        request.cases().size(), request.cases().stream().filter(c -> !c.reveal()).count(), result.millis(),
        result.exitCode());
    return response;
  }

  private static void validate(Wire.RunRequest r) {
    if (r == null || r.source() == null || r.signature() == null || r.cases() == null) {
      throw new IllegalArgumentException("a run needs source, signature and cases");
    }
    if (r.source().getBytes(StandardCharsets.UTF_8).length > SOURCE_LIMIT_BYTES) {
      throw new IllegalArgumentException("code is limited to 32 KB");
    }
    Wire.Signature sig = r.signature();
    if (sig.method() == null || !NAME.matcher(sig.method()).matches() || sig.params() == null
        || sig.returns() == null) {
      throw new IllegalArgumentException("a signature needs a method name, params and a return type");
    }
    for (Wire.Param p : sig.params()) {
      if (p == null || p.name() == null || !NAME.matcher(p.name()).matches() || p.type() == null) {
        throw new IllegalArgumentException("every parameter needs a name and a type");
      }
    }
    if (r.compare() != null && !r.compare().equals("exact") && !r.compare().equals("any-order")) {
      throw new IllegalArgumentException("unknown compare " + r.compare());
    }
    List<String> types = new ArrayList<>();
    types.add(r.signature().returns());
    r.signature().params().forEach(p -> types.add(p.type()));
    for (String t : types) {
      if (!JavaLiterals.TYPES.contains(t)) {
        throw new IllegalArgumentException("unsupported type " + t);
      }
    }
    if (r.cases().isEmpty() || r.cases().size() > MAX_CASES) {
      throw new IllegalArgumentException("a run has 1 to " + MAX_CASES + " cases");
    }
    Set<String> ids = new HashSet<>();
    for (Wire.CaseIn c : r.cases()) {
      if (c == null || c.id() == null || !ids.add(c.id())) {
        throw new IllegalArgumentException("case id " + (c == null ? null : c.id()) + " is missing or used twice");
      }
      if (c.args() == null || !c.args().isObject() || c.expected() == null) {
        throw new IllegalArgumentException("case " + c.id() + " needs args and an expected value");
      }
    }
  }

  private static Wire.RunResponse classify(Wire.RunRequest request, Sandbox.Result result) {
    boolean anyHidden = request.cases().stream().anyMatch(c -> !c.reveal());
    if (result.outputCut()) {
      return new Wire.RunResponse("crashed", null, List.of(), null);
    }
    HarnessOutput.Parsed parsed = HarnessOutput.parse(result.output());
    if (!parsed.started()) {
      if (LEARNER_DIAGNOSTIC.matcher(result.output()).find()) {
        return new Wire.RunResponse("compile-error", diagnostics(result.output()), List.of(), null);
      }
      if (HARNESS_DIAGNOSTIC.matcher(result.output()).find()) {
        return new Wire.RunResponse("signature-mismatch", "Solution needs a method: " + describe(request.signature()),
            List.of(), null);
      }
      return new Wire.RunResponse(result.timedOut() ? "timeout" : "crashed", null, List.of(), null);
    }
    List<Wire.CaseOut> cases = casesOut(request, parsed);
    String output = anyHidden ? null : parsed.output();
    String status;
    if (parsed.timedOut() != null || result.timedOut()) {
      status = "timeout";
    } else if (parsed.cases().stream().anyMatch(c -> c.error() != null && c.error().startsWith("OutOfMemoryError: Java heap space"))
        || (!parsed.ended() && result.exitCode() == 137 && !result.output().contains("\n@@EXIT"))) {
      status = "oom";
    } else if (!parsed.ended() || parsed.cases().size() < request.cases().size()) {
      status = "crashed";
    } else {
      status = "finished";
    }
    return new Wire.RunResponse(status, null, cases, output);
  }

  /** In request order; hidden cases keep only their verdict (runner design §5). */
  private static List<Wire.CaseOut> casesOut(Wire.RunRequest request, HarnessOutput.Parsed parsed) {
    Map<String, HarnessOutput.CaseLine> byId = new HashMap<>();
    parsed.cases().forEach(c -> byId.put(c.id(), c));
    List<Wire.CaseOut> out = new ArrayList<>();
    for (Wire.CaseIn c : request.cases()) {
      HarnessOutput.CaseLine line = byId.get(c.id());
      if (line == null) {
        continue;
      }
      out.add(c.reveal()
          ? new Wire.CaseOut(c.id(), line.verdict(), line.got(), line.error(), line.ms())
          : new Wire.CaseOut(c.id(), line.verdict(), null, null, null));
    }
    return List.copyOf(out);
  }

  /**
   * Only the learner's own diagnostic blocks. javac echoes the source line of every error, and in the
   * generated Main.java those lines hold the cases' literals, hidden ones included: a block from Main.java
   * is never shown. A block runs from its "File.java:N: error" header to the next header.
   */
  private static String diagnostics(String output) {
    StringBuilder out = new StringBuilder();
    boolean keep = false;
    for (String line : output.split("\n")) {
      java.util.regex.Matcher header = BLOCK_HEADER.matcher(line);
      if (header.find()) {
        keep = header.group(2).equals("Solution") || header.group(2).equals("TreeNode");
      } else if (SUMMARY.matcher(line).matches() || line.startsWith("@@")) {
        keep = false;
        continue;
      }
      if (keep) {
        out.append(line.replace("/work/", "")).append('\n');
      }
    }
    String text = out.toString().strip();
    return text.length() <= DIAGNOSTICS_LIMIT ? text : text.substring(0, DIAGNOSTICS_LIMIT) + "\n…";
  }

  private static String describe(Wire.Signature s) {
    List<String> params = new ArrayList<>();
    s.params().forEach(p -> params.add(p.type() + " " + p.name()));
    return s.returns() + " " + s.method() + "(" + String.join(", ", params) + ")";
  }
}
