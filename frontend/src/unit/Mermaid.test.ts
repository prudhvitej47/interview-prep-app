import { describe, expect, it } from "vitest";
import { diagramConfig, drawnWidth, laidOutByElk } from "./Mermaid";

// The settings that are easy to break without noticing: nothing fails loudly if they drift.
describe("diagram settings", () => {
  it("lays flowcharts out with ELK", () => {
    expect(diagramConfig(false, "flowchart TB\n  A --> B").layout).toBe("elk");
    expect(diagramConfig(true, "graph LR\n  A --> B").layout).toBe("elk");
  });

  // ELK throws on these, and the page then shows the diagram's source instead of a picture.
  it("leaves every other kind of diagram to draw itself", () => {
    for (const chart of ["mindmap\n  root((A))", "timeline\n  2020 : a", "quadrantChart\n  x-axis A --> B",
      "xychart-beta\n  bar [1]", "sequenceDiagram\n  A->>B: hi", "erDiagram\n  A ||--o{ B : has"]) {
      expect(diagramConfig(false, chart).layout).toBe("dagre");
      expect(laidOutByElk(chart)).toBe(false);
    }
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

describe("how wide a diagram is drawn", () => {
  it("never stretches a small diagram past the size Mermaid drew it", () => {
    // A narrow top-to-bottom flowchart used to be forced to 640px, drawing its 16px labels at 61px.
    expect(drawnWidth(168, 16)).toEqual({ min: 105, max: 168 });
  });

  it("lets a wide diagram shrink only until its smallest label reaches 10px", () => {
    // A 2264px mindmap with 16px labels may shrink to 1415px; below that it scrolls.
    expect(drawnWidth(2264, 16)).toEqual({ min: 1415, max: 2264 });
  });

  it("never shrinks a diagram whose labels are already small", () => {
    expect(drawnWidth(1190, 8).min).toBe(1190);
  });

  it("leaves a diagram with no measurable width alone", () => {
    expect(drawnWidth(0, 16)).toEqual({ min: 0, max: 0 });
  });
});
