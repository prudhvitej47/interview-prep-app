package com.interviewprep.runner;

import java.time.Clock;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * The runner process (runner design §3): same image as the app, different entrypoint (deploy/
 * docker-compose.yml). It alone may start containers; it never runs learner code itself.
 *
 * <p>Environment: RUNNER_PORT (8090), RUNNER_DOCKER (docker), RUNNER_IMAGE (the pinned Temurin digest),
 * RUNNER_RUNTIME (unset, or e.g. runsc). {@code smoke} as the first argument does one tiny real run and
 * exits 0 if it passed: the deployment check.
 */
public final class RunnerMain {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private RunnerMain() {}

  public static void main(String[] args) {
    String docker = env("RUNNER_DOCKER", "docker");
    String image = env("RUNNER_IMAGE", DockerSandbox.DEFAULT_IMAGE);
    DockerSandbox sandbox = new DockerSandbox(docker, image, System.getenv("RUNNER_RUNTIME"));
    RunService service = new RunService(sandbox);
    if (args.length > 0 && args[0].equals("smoke")) {
      String result = smoke(service);
      System.out.println(result);
      System.exit(result.equals("finished pass") ? 0 : 1);
    }
    new Sweeper(docker, Clock.systemUTC()).start();
    int port = Integer.parseInt(env("RUNNER_PORT", "8090"));
    int bound = new RunnerServer(service, sandbox, 20_000).start(port);
    System.getLogger(RunnerMain.class.getName()).log(System.Logger.Level.INFO,
        "Code runner listening on port {0} (image {1})", bound, image);
  }

  /** One tiny run through the real sandbox: "finished pass" when Docker, the image and the harness all work. */
  static String smoke(RunService service) {
    Wire.RunResponse r = service.run(new Wire.RunRequest("class Solution { int f(int x) { return x + 1; } }",
        new Wire.Signature("f", List.of(new Wire.Param("x", "int")), "int"), "exact",
        List.of(new Wire.CaseIn("smoke", JSON.readTree("{\"x\": 1}"), JSON.readTree("2"), true))));
    return r.status() + " " + (r.cases().isEmpty() ? "-" : r.cases().get(0).verdict());
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
