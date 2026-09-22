package com.interviewprep.evidence;

import com.interviewprep.curriculum.CurriculumReleased;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Takes the evidence module's share of each curriculum release: companies and interview reports.
 *
 * <p>A plain {@code @EventListener}, not Modulith's asynchronous one: it runs in the loader's
 * transaction, so a report that fails to load rolls the whole release back instead of leaving
 * units that cite evidence which is not there.
 *
 * <p>Evidence is replaced wholesale on every release. No learner data points at it, there are a few
 * dozen rows, and a full replace is obviously correct where a diff would have to be proven so.
 */
@Component
class EvidenceLoader {

  private final NamedParameterJdbcTemplate jdbc;

  EvidenceLoader(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @EventListener
  void on(CurriculumReleased release) {
    JsonNode content = release.content();

    // Upserted, not replaced: evidence rows refer to companies. A company dropped from the
    // taxonomy simply lingers, which is harmless.
    for (JsonNode c : content.path("companies")) {
      jdbc.update(
          "insert into company (id, name, category, aliases)"
              + " values (:id, :name, :category,"
              + " array(select jsonb_array_elements_text(cast(:aliases as jsonb))))"
              + " on conflict (id) do update set name = excluded.name,"
              + " category = excluded.category, aliases = excluded.aliases",
          new MapSqlParameterSource()
              .addValue("id", c.path("id").asString())
              .addValue("name", c.path("name").asString())
              .addValue("category", c.path("category").asString())
              .addValue("aliases", c.path("aliases").isArray() ? c.path("aliases").toString() : "[]"));
    }

    for (JsonNode r : content.path("rounds")) {
      jdbc.update("insert into round_type (id, name) values (:id, :name)"
              + " on conflict (id) do update set name = excluded.name",
          new MapSqlParameterSource().addValue("id", r.path("id").asString())
              .addValue("name", r.path("name").asString()));
    }

    // Cascades to evidence_item and evidence_item_topic.
    jdbc.update("delete from evidence", new MapSqlParameterSource());

    for (JsonNode e : content.path("evidence")) {
      JsonNode source = e.path("source");
      jdbc.update(
          "insert into evidence (id, company_id, role, level, location, interview_date,"
              + " report_date, source_kind, source_title, source_url, source_publisher, accessed,"
              + " tier, outcome, notes, state, release_id)"
              + " values (:id, :company, :role, :level, :location, :interviewDate, :reportDate,"
              + " :kind, :title, :url, :publisher, cast(:accessed as date), :tier, :outcome,"
              + " :notes, :state, :release)",
          new MapSqlParameterSource()
              .addValue("id", e.path("id").asString())
              .addValue("company", e.path("company").asString())
              .addValue("role", text(e.path("role")))
              .addValue("level", text(e.path("level")))
              .addValue("location", text(e.path("location")))
              .addValue("interviewDate", text(e.path("interview_date")))
              .addValue("reportDate", text(e.path("report_date")))
              .addValue("kind", source.path("kind").asString())
              .addValue("title", source.path("title").asString())
              .addValue("url", text(source.path("url")))
              .addValue("publisher", text(source.path("publisher")))
              .addValue("accessed", source.path("accessed").asString())
              .addValue("tier", e.path("tier").asString())
              .addValue("outcome", text(e.path("outcome")))
              .addValue("notes", text(e.path("notes")))
              .addValue("state", e.path("state").asString())
              .addValue("release", release.releaseId()));
      insertItems(e);
    }
  }

  /**
   * One item per paraphrased question, and one for a round that has a summary but no questions,
   * so every round the report describes is represented.
   */
  private void insertItems(JsonNode evidence) {
    String evidenceId = evidence.path("id").asString();
    int order = 0;
    int roundIndex = 0;
    for (JsonNode round : evidence.path("rounds")) {
      int index = roundIndex++;
      String roundType = round.path("type").asString();
      String summary = text(round.path("summary"));
      JsonNode questions = round.path("questions");
      if (!questions.isArray() || questions.isEmpty()) {
        insertItem(evidenceId, index, roundType, summary, null, order++);
        continue;
      }
      for (JsonNode q : questions) {
        long itemId = insertItem(evidenceId, index, roundType, summary, q.path("text").asString(), order++);
        for (JsonNode topic : q.path("topics")) {
          jdbc.update(
              "insert into evidence_item_topic (item_id, topic_id, confidence)"
                  + " values (:item, :topic, :confidence) on conflict do nothing",
              new MapSqlParameterSource()
                  .addValue("item", itemId)
                  .addValue("topic", topic.asString())
                  .addValue("confidence",
                      q.path("confidence").isNumber() ? q.path("confidence").asDouble() : null));
        }
      }
    }
  }

  private long insertItem(String evidenceId, int roundIndex, String roundType, String summary,
      String question, int order) {
    return jdbc.queryForObject(
        "insert into evidence_item (evidence_id, round_index, round_type, summary, question, sort_order)"
            + " values (:evidence, :index, :round, :summary, :question, :order) returning id",
        new MapSqlParameterSource()
            .addValue("evidence", evidenceId)
            .addValue("index", roundIndex)
            .addValue("round", roundType)
            .addValue("summary", summary)
            .addValue("question", question)
            .addValue("order", order),
        Long.class);
  }

  private static String text(JsonNode node) {
    return node.isMissingNode() || node.isNull() ? null : node.asString();
  }
}
