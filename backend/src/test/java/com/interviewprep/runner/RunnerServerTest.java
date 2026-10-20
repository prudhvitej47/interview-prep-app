package com.interviewprep.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The runner's HTTP surface, with a sandbox that can be held to make the runner busy. */
class RunnerServerTest {

  private static final String BODY = """
      {"source": "class Solution { int f(int x) { return x; } }",
       "signature": {"method": "f", "params": [{"name": "x", "type": "int"}], "returns": "int"},
       "compare": "exact",
       "cases": [{"id": "a", "args": {"x": 1}, "expected": 1, "reveal": true}]}
      """;
  private static final String OUTPUT = """
      @@START
      @@CASE {"case":"a","verdict":"pass","got":"1","error":null,"ms":2}
      @@OUTPUT ""
      @@END
      """;

  private final CountDownLatch release = new CountDownLatch(1);
  private volatile boolean hold;
  private RunnerServer server;
  private final HttpClient http = HttpClient.newHttpClient();

  private final Sandbox sandbox = new Sandbox() {
    @Override public Result run(Map<String, String> files) {
      if (hold) {
        try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      }
      return new Result(0, OUTPUT, false, false, 5);
    }
    @Override public boolean healthy() { return true; }
  };

  private int start(long busyWaitMs) {
    server = new RunnerServer(new RunService(sandbox), sandbox, busyWaitMs);
    return server.start(0);
  }

  @AfterEach
  void stop() {
    release.countDown();
    if (server != null) server.stop();
  }

  private HttpResponse<String> post(int port, String body) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/runs"))
        .POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json").build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void aRunAnswersWithItsResults() throws Exception {
    HttpResponse<String> r = post(start(1000), BODY);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("\"status\":\"finished\"").contains("\"verdict\":\"pass\"");
  }

  @Test
  void badRequestsAreRefusedWithoutRunning() throws Exception {
    int port = start(1000);
    assertThat(post(port, "{not json").statusCode()).isEqualTo(400);
    assertThat(post(port, BODY.replace("\"int\"}]", "\"short\"}]")).statusCode()).isEqualTo(400);
    assertThat(post(port, "x".repeat(RunnerServer.BODY_LIMIT + 1)).statusCode()).isEqualTo(413);
    HttpResponse<String> get = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/runs")).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(get.statusCode()).isEqualTo(405);
  }

  @Test
  void oneRunAtATimeAndTheNextIsToldTheRunnerIsBusy() throws Exception {
    int port = start(300);
    hold = true;
    CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> {
      try { return post(port, BODY); } catch (Exception e) { throw new IllegalStateException(e); }
    });
    Thread.sleep(200);
    HttpResponse<String> second = post(port, BODY);
    assertThat(second.statusCode()).isEqualTo(503);
    assertThat(second.body()).contains("\"status\":\"busy\"");
    release.countDown();
    assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
  }

  @Test
  void healthSaysWhetherDockerAndTheImageAreThere() throws Exception {
    int port = start(1000);
    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).isEqualTo("{\"healthy\":true}");
  }
}
