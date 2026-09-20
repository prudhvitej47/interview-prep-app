package com.interviewprep.curriculum;

/** What the front end needs to list a topic. Public because it crosses the module boundary. */
public record TopicSummary(String id, String domainId, String parentId, String name) {}
