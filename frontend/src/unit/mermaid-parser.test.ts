import { describe, expect, it } from "vitest";
import mermaid from "mermaid";

/**
 * The real Mermaid parser, not a mock.
 *
 * package.json overrides lodash-es to 4.18.1 to clear five high-severity advisories. Mermaid's
 * dependency chevrotain pins 4.17.23 exactly, so this checks the override did not break it: the pie
 * chart goes through Mermaid's chevrotain-based parser, the sequence diagram through the other one.
 * Parsing needs no layout, so it runs here without a real browser.
 */
describe("Mermaid parsing with the patched lodash-es", () => {
  it("parses a pie chart, which uses the chevrotain-based parser", async () => {
    await expect(mermaid.parse('pie title Study time\n  "DSA" : 20\n  "HLD" : 12')).resolves.toBeTruthy();
  });

  it("parses the sequence diagram from the idempotency-keys unit", async () => {
    const diagram = [
      "sequenceDiagram",
      "  participant C as Client",
      "  participant S as Payment API",
      "  C->>S: POST /charges (key K, ₹500)",
      "  S--xC: response lost (timeout)",
      "  C->>S: retry POST /charges (key K, ₹500)",
      "  S-->>C: saved response (no second charge)",
    ].join("\n");
    await expect(mermaid.parse(diagram)).resolves.toBeTruthy();
  });

  it("rejects a malformed diagram rather than accepting anything", async () => {
    await expect(mermaid.parse("sequenceDiagram\n  C->>: missing target")).rejects.toThrow();
  });
});
