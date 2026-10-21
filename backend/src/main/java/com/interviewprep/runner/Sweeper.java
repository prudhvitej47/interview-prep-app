package com.interviewprep.runner;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Removes sandbox containers left behind by a crash (runner design §7): anything labelled
 * interview-prep.run=1 and older than a minute. A run lasts at most about 15 s, so nothing in progress
 * is ever that old, and nothing without the label (the app, Postgres) is ever looked at.
 */
final class Sweeper {

  static final long MAX_AGE_SECONDS = 60;

  // Reads each docker command's output, so a command that never exits can be killed at the limit.
  private static final ExecutorService READERS = Executors.newVirtualThreadPerTaskExecutor();

  private final String docker;
  private final Clock clock;
  private final Duration limit;

  Sweeper(String docker, Clock clock) {
    this(docker, clock, Duration.ofSeconds(15));
  }

  /** {@code limit}: how long one docker command may take before it is killed (a stalled daemon). */
  Sweeper(String docker, Clock clock, Duration limit) {
    this.docker = docker;
    this.clock = clock;
    this.limit = limit;
  }

  int sweepOnce() {
    List<String> ids = lines(run(docker, "ps", "-aq", "--filter", "label=interview-prep.run=1").out());
    if (ids.isEmpty()) {
      return 0;
    }
    List<String> inspect = new ArrayList<>(List.of(docker, "inspect", "--format", "{{.Id}} {{.Created}}"));
    inspect.addAll(ids);
    int removed = 0;
    for (String line : lines(run(inspect.toArray(String[]::new)).out())) {
      String[] parts = line.split(" ", 2);
      if (parts.length != 2) {
        continue;
      }
      Instant created;
      try {
        created = Instant.parse(parts[1].strip());
      } catch (DateTimeParseException e) {
        continue;
      }
      if (Duration.between(created, clock.instant()).getSeconds() > MAX_AGE_SECONDS) {
        if (run(docker, "rm", "-f", parts[0]).ok()) {
          removed++;
        }
      }
    }
    return removed;
  }

  void start() {
    ScheduledExecutorService every = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "sandbox-sweeper");
      t.setDaemon(true);
      return t;
    });
    every.scheduleWithFixedDelay(() -> {
      try {
        int removed = sweepOnce();
        if (removed > 0) {
          System.getLogger(Sweeper.class.getName()).log(System.Logger.Level.INFO,
              "removed {0} stale sandbox container(s)", removed);
        }
      } catch (RuntimeException e) {
        System.getLogger(Sweeper.class.getName()).log(System.Logger.Level.WARNING,
            "sweep failed: {0}", e.getClass().getName());
      }
    }, 0, 60, TimeUnit.SECONDS);
  }

  private record Ran(String out, boolean ok) {}

  /**
   * Runs one docker command, reading its output on its own thread: a docker that never exits (a stalled
   * daemon) is killed at the limit instead of holding the sweeper thread for good.
   */
  private Ran run(String... command) {
    Process p = null;
    try {
      p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      InputStream in = p.getInputStream();
      CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> {
        try {
          return in.readAllBytes();
        } catch (IOException e) {
          return new byte[0];
        }
      }, READERS);
      if (!p.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
        p.destroyForcibly();
        return new Ran("", false);
      }
      return new Ran(new String(out.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8), p.exitValue() == 0);
    } catch (IOException | ExecutionException | TimeoutException e) {
      if (p != null) {
        p.destroyForcibly();
      }
      return new Ran("", false);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (p != null) {
        p.destroyForcibly();
      }
      return new Ran("", false);
    }
  }

  private static List<String> lines(String text) {
    return text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
  }
}
