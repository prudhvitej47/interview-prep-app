package com.interviewprep.curriculum;

import java.util.List;

/** A domain as a learner sees it when rating themselves: what it is, and a few examples of it. */
public record DomainSummary(String id, String name, int weight, List<String> examples) {}
