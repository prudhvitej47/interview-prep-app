import { describe, expect, it } from "vitest";
import { expandMacros } from "./sketchMacros";
import { CAST, DSA, KINDS, STATE } from "./sketchPalette";
import { routeArrows } from "./Sketch";

type El = Record<string, unknown>;
const byType = (els: El[], type: string) => els.filter((e) => e.type === type);
const byId = (els: El[], id: string) => els.find((e) => e.id === id) as El;

describe("elements that are not building blocks", () => {
  it("pass through untouched", () => {
    const plain = { type: "rectangle", id: "a", x: 0, y: 0, width: 10, height: 10 };
    expect(expandMacros([plain])[0]).toBe(plain);
  });

  it("refuse an unknown building block, so the fence shows its source", () => {
    expect(() => expandMacros([{ type: "x-foo" }])).toThrow("unknown sketch macro x-foo");
  });
});

describe("x-box", () => {
  it("is a rounded box in its kind's colours", () => {
    for (const [kind, colours] of Object.entries(KINDS)) {
      if (kind === "datastore" || kind === "queue") continue;  // drawn by x-db and x-log
      const [box] = expandMacros([{ type: "x-box", id: "b", x: 0, y: 0, label: "API", kind }]);
      expect(box).toMatchObject({ type: "rectangle", id: "b", roundness: { type: 3 },
        backgroundColor: colours.fill, strokeColor: colours.stroke, fillStyle: "solid", label: { text: "API" } });
    }
  });

  it("refuses a kind that is not in the palette", () => {
    expect(() => expandMacros([{ type: "x-box", id: "b", x: 0, y: 0, label: "?", kind: "shiny" }]))
      .toThrow("x-box kind shiny");
  });
});

describe("x-db", () => {
  it("draws a green cylinder and keeps the id on a box arrows can bind to", () => {
    const els = expandMacros([{ type: "x-db", id: "pg", x: 100, y: 50, width: 140, height: 100, label: "Postgres" }]);
    expect(byType(els, "ellipse")).toHaveLength(2);  // top and bottom caps
    for (const cap of byType(els, "ellipse")) {
      expect(cap).toMatchObject({ backgroundColor: KINDS.datastore.fill, strokeColor: KINDS.datastore.stroke });
    }
    expect(byId(els, "pg")).toMatchObject({ x: 100, y: 50, width: 140, height: 100, label: { text: "Postgres" } });
  });
});

describe("x-log", () => {
  const log = { type: "x-log", id: "p0", x: 10, y: 20, cells: ["a", "b", "c", "d", "e"], offsets: 100 };

  it("draws one yellow cell per record and an offset above each", () => {
    const els = expandMacros([log]);
    const cells = byType(els, "rectangle").filter((e) => e.id !== "p0");
    expect(cells).toHaveLength(5);
    expect(cells[3]).toMatchObject({ x: 10 + 3 * 56, y: 20, backgroundColor: KINDS.queue.fill });
    const offsets = byType(els, "text").map((t) => t.text);
    expect(offsets).toEqual(["100", "101", "102", "103", "104"]);
  });

  it("points at a cell with a labelled arrow from below", () => {
    const els = expandMacros([{ ...log, pointers: [{ at: 2, label: "ledger @102" }] }]);
    const arrow = byType(els, "arrow")[0];
    const tip = arrow.points as number[][];
    expect((arrow.x as number) + tip[1][0]).toBe(10 + 2 * 56 + 28);  // centre of cell 2
    expect(byType(els, "text").some((t) => t.text === "ledger @102")).toBe(true);
  });

  it("lets an arrow bind to the whole strip by its id", () => {
    const els = routeArrows([
      ...expandMacros([log]),
      { type: "rectangle", id: "producer", x: 10, y: 300, width: 100, height: 50 },
      { type: "arrow", start: { id: "producer" }, end: { id: "p0" } },
    ]);
    const arrow = els[els.length - 1];
    expect(arrow.points).toBeDefined();  // routed, not a stub
  });
});

describe("x-array", () => {
  it("draws cells with indices, and colours the current, answer and ruled-out cells", () => {
    const els = expandMacros([{ type: "x-array", id: "a", x: 0, y: 0, cells: [1, 3, 4, 6],
      current: [1], answer: [3], shade: [0] }]);
    const cells = byType(els, "rectangle").filter((e) => e.id !== "a");
    expect(cells.map((c) => c.x)).toEqual([0, 56, 112, 168]);
    expect(cells[1].backgroundColor).toBe(DSA.current);
    expect(cells[3].backgroundColor).toBe(DSA.answer);
    expect(cells[0].fillStyle).toBe("hachure");
    expect(byType(els, "text").map((t) => t.text).filter((t) => /^\d$/.test(String(t)))).toEqual(["0", "1", "2", "3"]);
  });

  it("draws pointers in blue", () => {
    const els = expandMacros([{ type: "x-array", id: "a", x: 0, y: 0, cells: [1, 2], pointers: [{ at: 0, label: "left" }] }]);
    expect(byType(els, "arrow")[0].strokeColor).toBe(DSA.pointer);
  });

  it("crosses out a cell in red", () => {
    const els = expandMacros([{ type: "x-array", id: "a", x: 0, y: 0, cells: [1, 2], cross: [1] }]);
    const lines = byType(els, "line").filter((l) => l.strokeColor === STATE.failure);
    expect(lines).toHaveLength(2);
  });
});

describe("x-grid", () => {
  it("lays rows out as cells and highlights the ones asked for", () => {
    const els = expandMacros([{ type: "x-grid", id: "g", x: 0, y: 0, rows: [[1, 1], [1, 2]], highlight: [[1, 1]] }]);
    const cells = byType(els, "rectangle").filter((e) => e.id !== "g");
    expect(cells).toHaveLength(4);
    expect(cells[3]).toMatchObject({ x: 56, y: 40, backgroundColor: DSA.current });
  });
});

describe("x-tree", () => {
  const nodes = [
    { id: "r", label: "8000" },
    { id: "l", label: "12000", parent: "r" },
    { id: "rr", label: "11000", parent: "r" },
  ];

  it("places children a level below and their parent above their middle", () => {
    const els = expandMacros([{ type: "x-tree", id: "h", x: 0, y: 0, nodes }]);
    const root = byId(els, "h/r"), left = byId(els, "h/l"), right = byId(els, "h/rr");
    expect(left.y).toBe((root.y as number) + 90);
    expect(right.y).toBe(left.y);
    const centre = (e: El) => (e.x as number) + (e.width as number) / 2;
    expect(centre(root)).toBe((centre(left) + centre(right)) / 2);
  });

  it("joins parent and child with an edge the arrow router draws", () => {
    const els = expandMacros([{ type: "x-tree", id: "h", x: 0, y: 0, nodes }]);
    const edges = byType(els, "arrow");
    expect(edges).toHaveLength(2);
    expect(edges[0]).toMatchObject({ start: { id: "h/r" }, end: { id: "h/l" }, endArrowhead: null });
  });

  it("refuses a parent that does not exist", () => {
    expect(() => expandMacros([{ type: "x-tree", id: "h", x: 0, y: 0, nodes: [{ id: "a", label: "a", parent: "zz" }] }]))
      .toThrow("x-tree h: node a has unknown parent zz");
  });
});

describe("x-lanes", () => {
  const lanes = { type: "x-lanes", id: "t", x: 0, y: 0, lanes: ["Worker A", "Lock service"], ticks: ["t0", "t1", "t2", "t3"] };

  it("draws a time axis down the left and one header per lane", () => {
    const els = expandMacros([lanes]);
    const texts = byType(els, "text").map((t) => t.text);
    expect(texts).toEqual(expect.arrayContaining(["t0", "t3", "Worker A", "Lock service"]));
  });

  it("draws a span as a bar from one tick to another", () => {
    const els = expandMacros([{ ...lanes, bars: [{ lane: "Worker A", from: "t1", to: "t3", label: "lease, token 33" }] }]);
    const bar = byType(els, "rectangle").find((r) => r.id === undefined && (r.height as number) > 0) as El;
    expect(bar.height).toBe(2 * 48);
    expect(bar.backgroundColor).toBe(KINDS.service.fill);
  });

  it("marks a failure with a red cross", () => {
    const els = expandMacros([{ ...lanes, marks: [{ lane: "Lock service", at: "t2", label: "rejected", state: "failure" }] }]);
    expect(byType(els, "line").filter((l) => l.strokeColor === STATE.failure)).toHaveLength(2);
  });

  it("refuses a bar on a lane that does not exist", () => {
    expect(() => expandMacros([{ ...lanes, bars: [{ lane: "Worker C", from: "t0", to: "t1" }] }]))
      .toThrow("x-lanes t: unknown lane Worker C");
  });
});

describe("x-ring", () => {
  it("puts a token at 90 degrees on the right-hand side, measured clockwise from 12 o'clock", () => {
    const els = expandMacros([{ type: "x-ring", id: "ring", cx: 200, cy: 200, r: 100, tokens: [{ angle: 90, label: "B", id: "b" }] }]);
    const token = byId(els, "b");
    expect((token.x as number) + (token.width as number) / 2).toBe(300);
    expect((token.y as number) + (token.height as number) / 2).toBe(200);
  });

  it("puts a token at 0 degrees at the top", () => {
    const els = expandMacros([{ type: "x-ring", id: "ring", cx: 200, cy: 200, r: 100, tokens: [{ angle: 0, label: "A", id: "a" }] }]);
    const token = byId(els, "a");
    expect((token.y as number) + (token.height as number) / 2).toBe(100);
  });
});

describe("x-lock, x-cross", () => {
  it("draws a cross as two red lines", () => {
    const els = expandMacros([{ type: "x-cross", x: 0, y: 0 }]);
    expect(els).toHaveLength(2);
    expect(els.every((e) => e.type === "line" && e.strokeColor === STATE.failure)).toBe(true);
  });

  it("draws a padlock from a body and a shackle", () => {
    const els = expandMacros([{ type: "x-lock", x: 0, y: 0 }]);
    expect(byType(els, "rectangle")).toHaveLength(1);
    expect(byType(els, "line")).toHaveLength(1);
  });
});

describe("x-actor", () => {
  it("draws a cast member as a stick figure with the role's name, bindable by id", () => {
    const els = expandMacros([{ type: "x-actor", id: "m", x: 0, y: 0, role: "merchant" }]);
    expect(byType(els, "ellipse")).toHaveLength(1);  // head
    expect(byType(els, "line").length).toBeGreaterThanOrEqual(3);  // body, arms, legs
    expect(byType(els, "text").map((t) => t.text)).toContain("merchant");
    expect(byId(els, "m")).toBeDefined();
  });

  it("refuses anyone who is not in the cast", () => {
    expect(CAST).toContain("on-call-engineer");
    expect(() => expandMacros([{ type: "x-actor", id: "z", x: 0, y: 0, role: "wizard" }]))
      .toThrow("x-actor role wizard is not in the cast");
  });
});

describe("determinism", () => {
  it("expands the same input to the same output every time", () => {
    const input = [{ type: "x-ring", id: "r", cx: 0, cy: 0, r: 50, tokens: [{ angle: 45, label: "A" }] },
      { type: "x-db", id: "d", x: 0, y: 0, label: "db" }];
    expect(JSON.stringify(expandMacros(input))).toBe(JSON.stringify(expandMacros(input)));
  });
});

describe("found by rendering", () => {
  it("writes every block's label in ink, never in a transparent or coloured stroke it inherits", () => {
    const els = expandMacros([
      { type: "x-db", id: "pg", x: 0, y: 0, label: "Postgres" },
      { type: "x-box", id: "b", x: 0, y: 0, label: "API", kind: "service" },
      { type: "x-log", id: "l", x: 0, y: 0, cells: ["r"] },
    ]);
    const labels = els.filter((e) => e.label).map((e) => (e.label as Record<string, unknown>).strokeColor);
    expect(labels.length).toBe(3);
    expect(labels.every((c) => c === "#1e1e1e")).toBe(true);
  });

  it("makes tree nodes wide enough for their longest label", () => {
    const els = expandMacros([{ type: "x-tree", id: "h", x: 0, y: 0, nodes: [{ id: "a", label: "250000" }] }]);
    // An ellipse only lays text out in about 70% of its width, so the width has to cover that.
    expect(byId(els, "h/a").width as number).toBeGreaterThanOrEqual((6 * 18 * 0.55 + 12) / 0.7);
  });

  it("keeps a table's dependency arrow off both cells' numbers", () => {
    const els = expandMacros([{ type: "x-grid", id: "g", x: 0, y: 0, rows: [[1, 2]], arrowsFrom: [[0, 0, 0, 1]] }]);
    const arrow = byType(els, "arrow")[0];
    const start = arrow.x as number, end = start + (arrow.points as number[][])[1][0];
    expect(start).toBeGreaterThan(28 + 10);   // clear of the first number, centred at 28
    expect(end).toBeLessThan(84 - 10);        // clear of the second, centred at 84
  });
});

describe("x-log's box for arrows", () => {
  it("covers the offsets row, so an arrow from above stops before the numbers", () => {
    const els = expandMacros([{ type: "x-log", id: "p0", x: 0, y: 100, cells: ["a"], offsets: 7 }]);
    const box = byId(els, "p0");
    const offset = byType(els, "text")[0];
    expect(box.y as number).toBeLessThanOrEqual(offset.y as number);
  });
});
