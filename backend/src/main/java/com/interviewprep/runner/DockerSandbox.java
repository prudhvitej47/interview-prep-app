package com.interviewprep.runner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Runs one set of files in a throwaway container with the flags of runner design §4: no network,
 * read-only root, nobody user, small tmpfs, 256 MB, one CPU, 64 processes, no capabilities. The command
 * is an argument list (no shell on the host), the source arrives as a tar on stdin, and the run is killed
 * after 12 s or 256 KB of output.
 */
final class DockerSandbox implements Sandbox {

  static final String DEFAULT_IMAGE =
      "eclipse-temurin:25-jdk-noble@sha256:a75cd2f1bd3e1e45da898bf658f1f1039ab7392b34844862514d230fd6a516d4";
  static final int OUTPUT_LIMIT = 256 * 1024;
  static final long WALL_CLOCK_MS = 12_000;

  private static final String COMMAND = "tar -xf - -C /work && exec java -XX:+UseSerialGC"
      + " -XX:TieredStopAtLevel=1 -XX:-UsePerfData -Xmx160m -XX:ActiveProcessorCount=1 /work/Main.java";

  private final String docker;
  private final String image;
  private final String runtime;

  DockerSandbox(String docker, String image, String runtime) {
    this.docker = docker;
    this.image = image;
    this.runtime = runtime;
  }

  List<String> runArgs(String name) {
    List<String> args = new ArrayList<>(List.of("run", "--rm", "-i", "--name", name,
        "--label", "interview-prep.run=1", "--network", "none", "--read-only", "--user", "65534:65534",
        "--tmpfs", "/work:size=8m,mode=1777,noexec,nosuid", "--tmpfs", "/tmp:size=8m,noexec,nosuid",
        "--memory", "256m", "--memory-swap", "256m", "--cpus", "1", "--pids-limit", "64",
        "--ulimit", "nofile=64:64", "--ulimit", "nproc=64:64", "--cap-drop", "ALL",
        "--security-opt", "no-new-privileges", "--ipc", "none", "--log-driver", "none",
        // The image is pre-pulled (plan 5d); a missing one fails fast instead of pulling inside a run.
        "--pull", "never"));
    if (runtime != null && !runtime.isBlank()) {
      args.add("--runtime");
      args.add(runtime);
    }
    args.addAll(List.of(image, "sh", "-c", COMMAND));
    return args;
  }

  @Override
  public Result run(Map<String, String> files) {
    String name = "ipr-run-" + UUID.randomUUID();
    List<String> command = new ArrayList<>();
    command.add(docker);
    command.addAll(runArgs(name));
    long start = System.nanoTime();
    Process p = null;
    try {
      p = new ProcessBuilder(command).redirectErrorStream(true).start();
      // Written on its own thread, inside the wall-clock window: a docker that never reads its input
      // (stalled daemon, a missing image) must not hold the runner's one permit forever.
      OutputStream stdin = p.getOutputStream();
      byte[] tar = Tar.of(files);
      Thread.ofVirtual().start(() -> {
        try (stdin) {
          stdin.write(tar);
        } catch (IOException e) {
          // The process was killed or exited early: its result says what happened.
        }
      });
      Reader reader = new Reader(p.getInputStream());
      Thread readThread = Thread.ofVirtual().start(reader);
      // Poll, so a run that floods its output is killed as soon as it passes the limit.
      long deadline = System.nanoTime() + WALL_CLOCK_MS * 1_000_000;
      boolean exited = false;
      while (!reader.cut && System.nanoTime() < deadline) {
        if (p.waitFor(100, TimeUnit.MILLISECONDS)) {
          exited = true;
          break;
        }
      }
      boolean done = exited && !reader.cut;
      if (!done) {
        stop(name, p);
      }
      readThread.join(2_000);
      long millis = (System.nanoTime() - start) / 1_000_000;
      int exit = p.isAlive() ? -1 : p.exitValue();
      return new Result(exit, reader.text(), !done && !reader.cut, reader.cut, millis);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      stop(name, p);
      throw new IllegalStateException("interrupted", e);
    }
  }

  /**
   * Kills the container. The client goes first: once we stop reading its output it blocks on a full pipe,
   * and while it is attached, docker kill and docker rm hang until their timeouts. Then the container,
   * because the client dying is not enough: the container would keep running.
   */
  private void stop(String name, Process client) {
    if (client != null) {
      client.destroyForcibly();
    }
    quietly(docker, "kill", name);
    quietly(docker, "rm", "-f", name);
  }

  private static void quietly(String... command) {
    try {
      new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .start().waitFor(10, TimeUnit.SECONDS);
    } catch (IOException e) {
      // Best effort: the sweeper (plan 5d) removes anything left behind.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean healthy() {
    try {
      Process p = new ProcessBuilder(docker, "image", "inspect", image).redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
      return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Reads at most OUTPUT_LIMIT bytes; past that it marks the run cut and stops reading. */
  private static final class Reader implements Runnable {
    private final InputStream in;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private volatile boolean cut;

    Reader(InputStream in) {
      this.in = in;
    }

    @Override
    public void run() {
      byte[] buffer = new byte[8192];
      try {
        int n;
        while ((n = in.read(buffer)) != -1) {
          int room = OUTPUT_LIMIT - bytes.size();
          bytes.write(buffer, 0, Math.min(room, n));
          if (n > room) {
            cut = true;
            return;
          }
        }
      } catch (IOException e) {
        // The process was killed: whatever was read is the output.
      }
    }

    synchronized String text() {
      return bytes.toString(StandardCharsets.UTF_8);
    }
  }
}
