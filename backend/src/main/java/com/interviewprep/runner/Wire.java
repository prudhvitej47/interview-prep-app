package com.interviewprep.runner;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The runner's wire format (runner design §5). The app builds requests from its database, never from
 * the browser; the runner itself drops detail of hidden cases before answering (5c).
 */
public final class Wire {
  private Wire() {}

  public record Param(String name, String type) {}

  public record Signature(String method, List<Param> params, String returns) {}

  /** {@code reveal} false: a hidden case, whose input, output and timing never leave the runner. */
  public record CaseIn(String id, JsonNode args, JsonNode expected, boolean reveal) {}

  public record RunRequest(String source, Signature signature, String compare, List<CaseIn> cases) {}

  /** {@code verdict}: pass, fail, error or timeout. */
  public record CaseOut(String id, String verdict, String got, String error, Long ms) {}

  /** {@code status}: finished, compile-error, signature-mismatch, timeout, oom, crashed or busy. */
  public record RunResponse(String status, String compileErrors, List<CaseOut> cases, String output) {}
}
