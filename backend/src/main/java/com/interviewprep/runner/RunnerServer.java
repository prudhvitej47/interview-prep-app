package com.interviewprep.runner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The runner's only door: POST /runs and GET /health on the Compose network (no published port, plan 5d).
 * One sandbox at a time: a second run waits for the permit, then gets 503 busy (runner design §6).
 */
final class RunnerServer {

  static final int BODY_LIMIT = 1024 * 1024;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final RunService service;
  private final Sandbox sandbox;
  private final long busyWaitMs;
  private final Semaphore permit = new Semaphore(1, true);
  private HttpServer http;

  RunnerServer(RunService service, Sandbox sandbox, long busyWaitMs) {
    this.service = service;
    this.sandbox = sandbox;
    this.busyWaitMs = busyWaitMs;
  }

  int start(int port) {
    try {
      http = HttpServer.create(new InetSocketAddress(port), 16);
    } catch (IOException e) {
      throw new IllegalStateException("cannot listen on " + port, e);
    }
    http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    http.createContext("/runs", this::runs);
    http.createContext("/health", this::health);
    http.start();
    return http.getAddress().getPort();
  }

  void stop() {
    if (http != null) {
      http.stop(0);
    }
  }

  private void runs(HttpExchange exchange) throws IOException {
    try (exchange) {
      if (!exchange.getRequestMethod().equals("POST")) {
        send(exchange, 405, Map.of("error", "POST only"));
        return;
      }
      byte[] body;
      try (InputStream in = exchange.getRequestBody()) {
        body = in.readNBytes(BODY_LIMIT + 1);
      }
      if (body.length > BODY_LIMIT) {
        send(exchange, 413, Map.of("error", "request too large"));
        return;
      }
      Wire.RunRequest request;
      try {
        request = JSON.readValue(body, Wire.RunRequest.class);
      } catch (JacksonException e) {
        send(exchange, 400, Map.of("error", "not a run request"));
        return;
      }
      if (!permit.tryAcquire(busyWaitMs, TimeUnit.MILLISECONDS)) {
        send(exchange, 503, new Wire.RunResponse("busy", null, List.of(), null));
        return;
      }
      try {
        send(exchange, 200, service.run(request));
      } catch (IllegalArgumentException e) {
        send(exchange, 400, Map.of("error", e.getMessage()));
      } finally {
        permit.release();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void health(HttpExchange exchange) throws IOException {
    try (exchange) {
      boolean healthy = sandbox.healthy();
      send(exchange, healthy ? 200 : 503, Map.of("healthy", healthy));
    }
  }

  private static void send(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(body);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
