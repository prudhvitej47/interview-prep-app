package com.interviewprep.evidence;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Puts files on a new branch of the content repository, through GitHub's REST API.
 *
 * <p>It only creates branches and adds files to them; it never writes to an existing branch, so it
 * does not touch {@code main} or anyone's work in progress. The token itself (fine-grained, the
 * content repository only) could write {@code main}, since GitHub's free plan does not enforce
 * branch protection on private repositories; the content repository's publish workflow therefore
 * only publishes commits that a pull request was merged as.
 *
 * <p>Unconfigured (no token) on a developer's machine and in tests; the page then says so.
 */
@Component
class ContentRepo {

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final JsonMapper json;
  private final String token;
  private final String repo;
  private final String api;

  ContentRepo(JsonMapper json,
      @Value("${app.content.github-token:}") String token,
      @Value("${app.content.repo:prudhvitej47/interview-prep-content}") String repo,
      @Value("${app.content.github-api:https://api.github.com}") String api) {
    this.json = json;
    this.token = token;
    this.repo = repo;
    this.api = api;
  }

  boolean configured() {
    return !token.isBlank();
  }

  /** Thrown when GitHub says no; the message is safe to show (it never contains the token). */
  static class Failed extends RuntimeException {
    Failed(String message) {
      super(message);
    }
  }

  /**
   * Creates a branch from {@code main} named {@code branch}, or {@code branch-2}, {@code -3}… if
   * that is taken, commits each file to it, and returns the branch actually used.
   */
  String send(String branch, Map<String, String> files, String message) {
    String base = call("GET", "/repos/" + repo + "/git/ref/heads/main", null, 200)
        .path("object").path("sha").asString();
    String used = branch;
    for (int i = 2; ; i++) {
      HttpResponse<String> created = raw("POST", "/repos/" + repo + "/git/refs",
          Map.of("ref", "refs/heads/" + used, "sha", base));
      if (created.statusCode() == 201) {
        break;
      }
      if (created.statusCode() != 422 || i > 20) {
        throw new Failed("GitHub refused to create the branch (" + created.statusCode() + ")");
      }
      used = branch + "-" + i;  // 422: the name is taken
    }
    for (var file : files.entrySet()) {
      call("PUT", "/repos/" + repo + "/contents/" + file.getKey(), Map.of(
          "message", message,
          "branch", used,
          "content", Base64.getEncoder().encodeToString(file.getValue().getBytes(StandardCharsets.UTF_8))), 201);
    }
    return used;
  }

  /** The branch itself on GitHub. */
  String branchUrl(String branch) {
    return "https://github.com/" + repo + "/tree/" + branch;
  }

  /** Where a learner can see the branch, and the pull request once it opens. */
  String pullRequestsFor(String branch) {
    return "https://github.com/" + repo + "/pulls?q=is%3Apr+head%3A" + branch.replace("/", "%2F");
  }

  private JsonNode call(String method, String path, Object body, int expected) {
    HttpResponse<String> response = raw(method, path, body);
    if (response.statusCode() != expected) {
      throw new Failed("GitHub answered " + response.statusCode() + " to " + method + " " + path);
    }
    return json.readTree(response.body());
  }

  private HttpResponse<String> raw(String method, String path, Object body) {
    if (!configured()) {
      throw new Failed("sending to the curriculum is not set up on this server");
    }
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(api + path))
        .timeout(Duration.ofSeconds(20))
        .header("Authorization", "Bearer " + token)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    try {
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (java.io.IOException e) {
      throw new Failed("could not reach GitHub: " + e.getClass().getSimpleName());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Failed("interrupted while talking to GitHub");
    }
  }
}
