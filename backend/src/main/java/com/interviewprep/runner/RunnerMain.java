package com.interviewprep.runner;

/**
 * The runner process (runner design §3): same image as the app, different entrypoint (plan 5d). It alone
 * may start containers; it never runs learner code itself.
 *
 * <p>Environment: RUNNER_PORT (8090), RUNNER_DOCKER (docker), RUNNER_IMAGE (the pinned Temurin digest),
 * RUNNER_RUNTIME (unset, or e.g. runsc).
 */
public final class RunnerMain {

  private RunnerMain() {}

  public static void main(String[] args) {
    String docker = env("RUNNER_DOCKER", "docker");
    String image = env("RUNNER_IMAGE", DockerSandbox.DEFAULT_IMAGE);
    String runtime = System.getenv("RUNNER_RUNTIME");
    int port = Integer.parseInt(env("RUNNER_PORT", "8090"));
    DockerSandbox sandbox = new DockerSandbox(docker, image, runtime);
    int bound = new RunnerServer(new RunService(sandbox), sandbox, 20_000).start(port);
    System.getLogger(RunnerMain.class.getName()).log(System.Logger.Level.INFO,
        "Code runner listening on port {0} (image {1})", bound, image);
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
