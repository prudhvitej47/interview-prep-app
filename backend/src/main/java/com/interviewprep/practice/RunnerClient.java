package com.interviewprep.practice;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The app's only way to the code runner: HTTP on the Compose network (runner design §3). It never imports
 * the runner's classes, so the runner stays liftable into its own service.
 */
@Component
class RunnerClient {

  sealed interface Outcome permits Answered, Busy, Unavailable {}

  /** The runner answered: 200 with a result, or 400 for a request it refused. */
  record Answered(int status, JsonNode body) implements Outcome {}

  record Busy() implements Outcome {}

  record Unavailable() implements Outcome {}

  // Longer than the runner's worst case: 20 s in its queue, a 12 s run, then up to 10 s each for docker
  // kill and rm and 2 s to read what was left, about 54 s. Shorter, and a run still going reads as down.
  static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(60);

  private final String url;
  private final JsonMapper json;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  RunnerClient(@Value("${app.runner.url:}") String url, JsonMapper json) {
    this.url = url == null ? "" : url.strip();
    this.json = json;
  }

  boolean configured() {
    return !url.isEmpty();
  }

  Outcome run(JsonNode request) {
    if (!configured()) {
      return new Unavailable();
    }
    try {
      HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url + "/runs"))
              .timeout(ANSWER_TIMEOUT)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(request)))
              .build(),
          HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() == 503) {
        return new Busy();
      }
      if (response.statusCode() == 200 || response.statusCode() == 400) {
        return new Answered(response.statusCode(), json.readTree(response.body()));
      }
      return new Unavailable();
    } catch (IOException | JacksonException | IllegalArgumentException e) {
      return new Unavailable();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Unavailable();
    }
  }
}
