package com.interviewprep.runner;

import java.util.Map;

/** Where a run's files are compiled and run. DockerSandbox is the real one; tests use fakes. */
interface Sandbox {

  /**
   * {@code output}: stdout and stderr together, at most {@link DockerSandbox#OUTPUT_LIMIT} bytes.
   * {@code timedOut}: killed after the wall-clock limit. {@code outputCut}: killed for printing too much.
   */
  record Result(int exitCode, String output, boolean timedOut, boolean outputCut, long millis) {}

  Result run(Map<String, String> files);

  boolean healthy();
}
