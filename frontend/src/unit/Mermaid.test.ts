import { describe, expect, it } from "vitest";
import { diagramConfig } from "./Mermaid";

// The settings that are easy to break without noticing: nothing fails loudly if they drift.
describe("diagram settings", () => {
  it("lays flowcharts out with ELK", () => {
    expect(diagramConfig(false).layout).toBe("elk");
    expect(diagramConfig(true).layout).toBe("elk");
  });

  it("keeps diagrams unable to carry script", () => {
    expect(diagramConfig(false).securityLevel).toBe("strict");
    expect(diagramConfig(true).securityLevel).toBe("strict");
  });

  it("never names a font, which would clip every label on a browser that lacks it", () => {
    for (const dark of [false, true]) {
      expect(JSON.stringify(diagramConfig(dark)).toLowerCase()).not.toContain("font");
    }
  });

  it("follows the reader's light or dark preference", () => {
    expect(diagramConfig(false).theme).toBe("base");
    expect(diagramConfig(true).theme).toBe("dark");
  });
});
