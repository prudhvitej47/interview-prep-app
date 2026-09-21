package com.interviewprep.curriculum;

/**
 * A topic in a listing. {@code unitCount} counts units the asking learner can see, including those
 * in the topic's subtopics, so an empty-looking topic really has nothing to open yet.
 */
public record TopicSummary(String id, String domainId, String parentId, String name, int unitCount) {}
