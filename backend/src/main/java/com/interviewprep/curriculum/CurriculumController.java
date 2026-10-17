package com.interviewprep.curriculum;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The curriculum as a learner browses it.
 *
 * <p>Takes the plain {@link Principal} rather than the learner module's type: the learner module
 * already depends on this one for the domain list, and depending back would be a cycle. The
 * principal's name is the learner's slug, which is all visibility needs.
 */
@RestController
class CurriculumController {

  private final CurriculumQueries queries;

  CurriculumController(CurriculumQueries queries) {
    this.queries = queries;
  }

  @GetMapping("/api/topics")
  List<TopicSummary> topics(Principal learner) {
    return queries.topics(learner.getName());
  }

  @GetMapping("/api/topics/{id}")
  CurriculumQueries.TopicDetail topic(@PathVariable String id, Principal learner) {
    return queries.topic(id, learner.getName()).orElseThrow(CurriculumController::notFound);
  }

  // A private unit that belongs to someone else is a 404, not a 403: the other learner should not
  // be able to tell that it exists.
  @GetMapping("/api/units/{id}")
  CurriculumQueries.UnitDetail unit(@PathVariable String id, Principal learner) {
    return queries.unit(id, learner.getName()).orElseThrow(CurriculumController::notFound);
  }

  // A lab file name from the content repository: plain names only, so a zip can never write
  // outside the learner's folder.
  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]*");

  /** The starter for a unit's laptop lab: its README and the files the learner runs and edits. */
  @GetMapping("/api/units/{id}/lab/starter.zip")
  ResponseEntity<byte[]> labStarter(@PathVariable String id, Principal learner) throws IOException {
    if (!queries.isVisible(id, learner.getName())) {
      throw notFound();
    }
    CurriculumQueries.LabDownload lab = queries.labDownload(id).orElseThrow(CurriculumController::notFound);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      add(zip, id + "/README.md", "# Lab: " + lab.title() + "\n\n" + lab.readme());
      for (CurriculumQueries.LabFile f : lab.files()) {
        if (SAFE_NAME.matcher(f.name()).matches()) {
          add(zip, id + "/" + f.name(), f.content());
        }
      }
    }
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id + "-lab.zip\"")
        .contentType(MediaType.parseMediaType("application/zip"))
        .body(bytes.toByteArray());
  }

  private static void add(ZipOutputStream zip, String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
}
