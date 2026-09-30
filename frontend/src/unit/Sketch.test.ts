import { describe, expect, it } from "vitest";
import { edgePoint, parseSketch, routeArrows } from "./Sketch";

describe("reading a sketch fence", () => {
  it("takes a title and the element skeletons", () => {
    const spec = parseSketch('{"title": " A VPC ", "elements": [{"type": "rectangle", "x": 0, "y": 0}]}');
    expect(spec.title).toBe("A VPC");
    expect(spec.elements).toHaveLength(1);
  });

  // The title is the figure's name for a screen reader, so a sketch without one is refused.
  it("refuses a sketch with no title or no elements", () => {
    expect(() => parseSketch('{"elements": [{"type": "rectangle"}]}')).toThrow("title");
    expect(() => parseSketch('{"title": "x", "elements": []}')).toThrow("elements");
    expect(() => parseSketch("[1, 2]")).toThrow();
    expect(() => parseSketch("not json")).toThrow();
  });
});

describe("routing arrows", () => {
  const box = { x: 0, y: 0, width: 100, height: 50 };

  it("leaves a box's edge, pushed out by the gap, on the side facing the target", () => {
    expect(edgePoint(box, { x: 50, y: 500 })).toEqual({ x: 50, y: 56 });   // straight down
    expect(edgePoint(box, { x: 500, y: 25 })).toEqual({ x: 106, y: 25 });  // straight right
    expect(edgePoint(box, { x: -500, y: 25 })).toEqual({ x: -6, y: 25 });  // straight left
  });

  it("draws an arrow that names both ends from one box's edge to the other's", () => {
    const [, , arrow] = routeArrows([
      { type: "rectangle", id: "a", x: 0, y: 0, width: 100, height: 50 },
      { type: "rectangle", id: "b", x: 0, y: 200, width: 100, height: 50 },
      { type: "arrow", start: { id: "a" }, end: { id: "b" } },
    ]);
    expect(arrow).toMatchObject({ x: 50, y: 56, points: [[0, 0], [0, 138]] });
  });

  it("leaves an arrow alone when the author gave its points, or an end is missing", () => {
    const drawn = { type: "arrow", x: 1, y: 2, points: [[0, 0], [10, 10]], start: { id: "a" }, end: { id: "b" } };
    const dangling = { type: "arrow", start: { id: "a" }, end: { id: "nowhere" } };
    const elements = [{ type: "rectangle", id: "a", x: 0, y: 0, width: 10, height: 10 }, drawn, dangling];
    const [, first, second] = routeArrows(elements);
    expect(first).toBe(drawn);
    expect(second).toBe(dangling);
  });
});

describe("preparing a sketch for drawing", () => {
  it("expands building blocks before routing arrows, so an arrow can aim at a block", async () => {
    const { prepareElements } = await import("./Sketch");
    const out = prepareElements([
      { type: "x-db", id: "pg", x: 0, y: 200, label: "Postgres" },
      { type: "x-box", id: "api", x: 0, y: 0, label: "API", kind: "service" },
      { type: "arrow", start: { id: "api" }, end: { id: "pg" } },
    ]);
    expect(out.some((e) => e.type === "x-db")).toBe(false);
    expect(out[out.length - 1].points).toBeDefined();
  });
});
