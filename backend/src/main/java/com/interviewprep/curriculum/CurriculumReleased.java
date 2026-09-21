package com.interviewprep.curriculum;

import tools.jackson.databind.JsonNode;

/**
 * A new curriculum release was just loaded. Published inside the loading transaction, so a listener
 * that fails rolls the whole release back rather than leaving half of it in place.
 *
 * <p>This is how other modules take their share of a bundle without the curriculum module knowing
 * they exist: the evidence module listens and loads the interview reports.
 *
 * @param releaseId the {@code curriculum_release} row the content belongs to
 * @param content the parsed {@code content.json}; each listener reads the part it owns
 */
public record CurriculumReleased(long releaseId, JsonNode content) {}
