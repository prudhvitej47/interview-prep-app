package com.interviewprep.evidence;

import com.interviewprep.learner.LearnerPrincipal;
import com.interviewprep.progress.ProgressQueries;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Add an article": an interview experience or article a learner read, sent as raw material to an
 * {@code inbox/} branch of the content repository. Unlike a debrief it is not yet evidence: an ingest
 * run reads it, paraphrases its questions, maps them to topics and drafts any units, then opens a
 * proposal for review. {@code inbox/} branches open no pull request by themselves, so nothing
 * unprocessed ever reaches review, and the raw text never needs to reach {@code main}.
 */
@RestController
class ArticleController {

  static final int MAX_TEXT = 200_000;

  private final ContentRepo contentRepo;
  private final NamedParameterJdbcTemplate jdbc;

  ArticleController(ContentRepo contentRepo, NamedParameterJdbcTemplate jdbc) {
    this.contentRepo = contentRepo;
    this.jdbc = jdbc;
  }

  record Article(String title, String url, String company, String text) {}

  record Sent(String branch, String url) {}

  @PostMapping("/api/inbox/articles")
  ResponseEntity<?> send(@AuthenticationPrincipal LearnerPrincipal me, @RequestBody Article article) {
    String title = trim(article == null ? null : article.title());
    String url = trim(article == null ? null : article.url());
    String text = trim(article == null ? null : article.text());
    String company = trim(article == null ? null : article.company());
    List<String> problems = new java.util.ArrayList<>();
    if (title == null) {
      problems.add("Give it a title.");
    }
    if (url != null && !url.matches("https?://\\S+")) {
      problems.add("The link must start with http:// or https://.");
    }
    if (text == null && url == null) {
      problems.add("Paste the text, or at least give the link.");
    }
    if (text != null && text.length() > MAX_TEXT) {
      problems.add("That is more than " + MAX_TEXT + " characters; paste the interview part only.");
    }
    if (company != null && !Boolean.TRUE.equals(jdbc.queryForObject(
        "select exists(select 1 from company where id = :id)", new MapSqlParameterSource("id", company),
        Boolean.class))) {
      problems.add("Unknown company.");
    }
    if (!problems.isEmpty()) {
      return ResponseEntity.unprocessableContent().body(new DraftController.Problems(problems));
    }
    if (!contentRepo.configured()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "sending is not set up");
    }

    // The inbox README's naming: YYYY-MM-DD-<company>-<short-title>.md, source and date on top.
    LocalDate today = LocalDate.now(ProgressQueries.STUDY_ZONE);
    String shortTitle = DraftController.slug(title);
    shortTitle = shortTitle.substring(0, Math.min(shortTitle.length(), 50)).replaceAll("-$", "");
    String name = today + "-" + (company == null ? "article" : company) + "-" + shortTitle;
    String file = "# " + title + "\n\n"
        + "- Source: " + (url == null ? "(no link; text pasted)" : url) + "\n"
        + "- Copied: " + today + "\n"
        + (company == null ? "" : "- Company: " + company + "\n")
        + "- Sent from the app; process with the ingest procedure.\n\n"
        + (text == null ? "(No text: read it from the link if it is public.)\n" : text + "\n");
    try {
      String branch = contentRepo.send("inbox/" + name, Map.of("inbox/" + name + ".md", file),
          "Add an article to the inbox: " + title);
      return ResponseEntity.ok(new Sent(branch, contentRepo.branchUrl(branch)));
    } catch (ContentRepo.Failed e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
  }

  private static String trim(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
