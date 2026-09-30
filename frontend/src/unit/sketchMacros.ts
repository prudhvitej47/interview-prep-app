import { CAST, DSA, INK, KINDS, MUTED, STATE, type Kind, type State } from "./sketchPalette";

type El = Record<string, unknown>;

/**
 * Building blocks for sketches: an element whose `type` starts with `x-` is replaced by the plain
 * Excalidraw skeletons that draw it, before arrows are routed. They exist so the shapes a course
 * reuses (a database, a Kafka log, an array with pointers, a timeline) look the same in every unit,
 * take their colours from the palette instead of being typed by hand, and cost one line of a fence
 * instead of twenty. Each is a pure function of its input: a sketch draws the same way every time.
 *
 * A block that arrows should reach keeps its `id` on an invisible box around the whole shape, so
 * `routeArrows` can aim at it like any other box.
 */
export function expandMacros(elements: El[]): El[] {
  return elements.flatMap((e) => {
    const type = String(e.type ?? "");
    if (!type.startsWith("x-")) return [e];
    const expand = MACROS[type];
    if (!expand) throw new Error(`unknown sketch macro ${type}`);
    return expand(e);
  });
}

const MACROS: Record<string, (e: El) => El[]> = {
  "x-box": box,
  "x-db": db,
  "x-log": log,
  "x-array": array,
  "x-grid": grid,
  "x-tree": tree,
  "x-lanes": lanes,
  "x-ring": ring,
  "x-lock": lock,
  "x-cross": (e) => cross(num(e.x), num(e.y), num(e.size, 24)),
  "x-actor": actor,
};

const LABEL = 20;   // box labels
const NOTE = 18;    // cell values, notes, arrow labels
const SMALL = 16;   // offsets, indices, ticks: the floor the content check allows

function num(value: unknown, fallback = 0): number {
  return typeof value === "number" ? value : fallback;
}

/** A rough width for placing a text's left edge so it looks centred. Excalifont averages ~0.55em. */
function textWidth(text: string, fontSize: number): number {
  return Math.ceil(text.length * fontSize * 0.55);
}

/** A container's label. Written in ink: a label otherwise inherits its box's stroke, which may be a hue or transparent. */
function lbl(value: unknown, fontSize: number): El {
  return { text: String(value ?? ""), fontSize, strokeColor: INK };
}

function text(value: string, x: number, y: number, fontSize: number, strokeColor = INK): El {
  return { type: "text", x, y, text: value, fontSize, strokeColor };
}

function centredText(value: string, cx: number, y: number, fontSize: number, strokeColor = INK): El {
  return text(value, Math.round(cx - textWidth(value, fontSize) / 2), y, fontSize, strokeColor);
}

function line(x: number, y: number, dx: number, dy: number, strokeColor = INK, extra: El = {}): El {
  return { type: "line", x, y, points: [[0, 0], [dx, dy]], strokeColor, ...extra };
}

/** An invisible box carrying the block's id, so arrows can bind to the whole shape. */
function anchor(id: unknown, x: number, y: number, width: number, height: number, extra: El = {}): El[] {
  if (typeof id !== "string") return [];
  return [{ type: "rectangle", id, x, y, width, height, strokeColor: "transparent", backgroundColor: "transparent", ...extra }];
}

function kindOf(e: El, block: string): Kind {
  const kind = String(e.kind ?? "service");
  if (!(kind in KINDS)) throw new Error(`${block} kind ${kind} is not in the palette`);
  return kind as Kind;
}

function stateColour(state: unknown): string | undefined {
  return typeof state === "string" && state in STATE ? STATE[state as State] : undefined;
}

function cross(x: number, y: number, size: number): El[] {
  const style = { strokeWidth: 3 };
  return [line(x, y, size, size, STATE.failure, style), line(x + size, y, -size, size, STATE.failure, style)];
}

function pointerArrow(cx: number, top: number, label: string, colour: string): El[] {
  const length = 36;
  return [
    { type: "arrow", x: cx, y: top + length, points: [[0, 0], [0, -length + 6]], strokeColor: colour },
    centredText(label, cx, top + length + 4, SMALL, colour),
  ];
}

function box(e: El): El[] {
  const kind = kindOf(e, "x-box");
  const { fill, stroke } = KINDS[kind];
  return [{
    type: "rectangle", id: e.id, x: num(e.x), y: num(e.y), width: num(e.width, 160), height: num(e.height, 70),
    roundness: { type: 3 }, backgroundColor: fill, strokeColor: stroke, fillStyle: "solid",
    label: lbl(e.label, num(e.fontSize, LABEL)),
  }];
}

/** A cylinder: bottom cap, body, sides, top cap, in that order so each covers the right part. */
function db(e: El): El[] {
  const x = num(e.x), y = num(e.y), w = num(e.width, 140), h = num(e.height, 100);
  const { fill, stroke } = KINDS.datastore;
  const cap = Math.max(10, Math.min(18, Math.round(h / 5)));  // half the cap's height
  const shape = { backgroundColor: fill, strokeColor: stroke, fillStyle: "solid" };
  return [
    { type: "ellipse", x, y: y + h - 2 * cap, width: w, height: 2 * cap, ...shape },
    { type: "rectangle", x, y: y + cap, width: w, height: h - 2 * cap, ...shape, strokeColor: "transparent" },
    line(x, y + cap, 0, h - 2 * cap, stroke),
    line(x + w, y + cap, 0, h - 2 * cap, stroke),
    { type: "ellipse", x, y, width: w, height: 2 * cap, ...shape },
    ...anchor(e.id, x, y, w, h, { label: lbl(e.label, num(e.fontSize, LABEL)) }),
  ];
}

/** A strip of cells: a partition, a queue, a buffer. Offsets above; pointers (consumers) below. */
function log(e: El): El[] {
  const x = num(e.x), y = num(e.y), w = num(e.cellWidth, 56), h = num(e.cellHeight, 48);
  const cells = Array.isArray(e.cells) ? e.cells.map(String) : [];
  const kind = e.kind === undefined ? "queue" : kindOf(e, "x-log");
  const { fill, stroke } = KINDS[kind];
  const out: El[] = cells.map((value, i) => ({
    type: "rectangle", x: x + i * w, y, width: w, height: h, backgroundColor: fill, strokeColor: stroke,
    fillStyle: "solid", label: lbl(value, NOTE),
  }));
  if (typeof e.offsets === "number") {
    cells.forEach((_, i) => out.push(centredText(String((e.offsets as number) + i), x + i * w + w / 2, y - 24, SMALL, MUTED)));
  }
  for (const p of (e.pointers as { at: number; label: string }[] | undefined) ?? []) {
    out.push(...pointerArrow(x + p.at * w + w / 2, y + h, p.label, INK));
  }
  // The arrow box includes the offsets row, so an arrow from above stops before the numbers.
  const above = typeof e.offsets === "number" ? 28 : 0;
  return [...out, ...anchor(e.id, x, y - above, cells.length * w, h + above)];
}

/** An array with indices above, pointers below, and the DSA colours for current, answer and ruled out. */
function array(e: El): El[] {
  const x = num(e.x), y = num(e.y), w = num(e.cellWidth, 56), h = num(e.cellHeight, 48);
  const cells = Array.isArray(e.cells) ? e.cells.map(String) : [];
  const has = (key: string, i: number) => Array.isArray(e[key]) && (e[key] as number[]).includes(i);
  const out: El[] = cells.map((value, i) => {
    const fill = has("answer", i) ? DSA.answer : has("current", i) ? DSA.current : has("shade", i) ? DSA.ruledOut : DSA.cell;
    return {
      type: "rectangle", x: x + i * w, y, width: w, height: h, strokeColor: INK, backgroundColor: fill,
      fillStyle: has("shade", i) ? "hachure" : "solid", label: lbl(value, NOTE),
    };
  });
  if (e.indices !== false) {
    cells.forEach((_, i) => out.push(centredText(String(i), x + i * w + w / 2, y - 24, SMALL, MUTED)));
  }
  for (const p of (e.pointers as { at: number; label: string }[] | undefined) ?? []) {
    out.push(...pointerArrow(x + p.at * w + w / 2, y + h, p.label, DSA.pointer));
  }
  for (const i of (e.cross as number[] | undefined) ?? []) out.push(...cross(x + i * w + 12, y + 12, h - 24));
  return [...out, ...anchor(e.id, x, y, cells.length * w, h)];
}

/** A table of cells: a DP table, a seat map. */
function grid(e: El): El[] {
  const x = num(e.x), y = num(e.y), w = num(e.cellWidth, 56), h = num(e.cellHeight, 40);
  const rows = (Array.isArray(e.rows) ? e.rows : []) as unknown[][];
  const inList = (key: string, r: number, c: number) =>
    Array.isArray(e[key]) && (e[key] as number[][]).some(([rr, cc]) => rr === r && cc === c);
  const out: El[] = [];
  rows.forEach((row, r) => row.forEach((value, c) => {
    const fill = inList("answer", r, c) ? DSA.answer : inList("highlight", r, c) ? DSA.current : DSA.cell;
    out.push({ type: "rectangle", x: x + c * w, y: y + r * h, width: w, height: h, strokeColor: INK,
      backgroundColor: fill, fillStyle: "solid", label: lbl(value, NOTE) });
  }));
  ((e.colLabels as string[] | undefined) ?? []).forEach((label, c) =>
    out.push(centredText(label, x + c * w + w / 2, y - 24, SMALL, MUTED)));
  ((e.rowLabels as string[] | undefined) ?? []).forEach((label, r) =>
    out.push(text(label, x - textWidth(label, SMALL) - 10, y + r * h + (h - SMALL) / 2, SMALL, MUTED)));
  for (const [r, c, r2, c2] of (e.arrowsFrom as number[][] | undefined) ?? []) {
    // Centre to centre would run over both numbers; start and stop 18 px out from each centre instead.
    const cx1 = x + c * w + w / 2, cy1 = y + r * h + h / 2, cx2 = x + c2 * w + w / 2, cy2 = y + r2 * h + h / 2;
    const len = Math.hypot(cx2 - cx1, cy2 - cy1) || 1, ux = (cx2 - cx1) / len, uy = (cy2 - cy1) / len;
    const fx = Math.round(cx1 + ux * 18), fy = Math.round(cy1 + uy * 18);
    const tx = Math.round(cx2 - ux * 18), ty = Math.round(cy2 - uy * 18);
    out.push({ type: "arrow", x: fx, y: fy, points: [[0, 0], [tx - fx, ty - fy]], strokeColor: DSA.pointer });
  }
  const width = Math.max(0, ...rows.map((row) => row.length)) * w;
  return [...out, ...anchor(e.id, x, y, width, rows.length * h)];
}

/** A tree laid out by depth: leaves side by side in order, each parent above the middle of its children. */
function tree(e: El): El[] {
  const x = num(e.x), y = num(e.y), size = num(e.nodeSize, 56);
  const levelGap = num(e.levelGap, 90), gap = num(e.siblingGap, 24);
  const id = String(e.id);
  const nodes = (Array.isArray(e.nodes) ? e.nodes : []) as { id: string; label: string; parent?: string }[];
  // Every node as wide as the longest label needs. An ellipse lays text out in about 70% of its width.
  const width = Math.max(size, ...nodes.map((n) => Math.ceil((textWidth(String(n.label), NOTE) + 12) / 0.7)));
  const known = new Set(nodes.map((n) => n.id));
  for (const n of nodes) {
    if (n.parent !== undefined && !known.has(n.parent)) throw new Error(`x-tree ${id}: node ${n.id} has unknown parent ${n.parent}`);
  }
  const children = (parent?: string) => nodes.filter((n) => n.parent === parent);
  const centre = new Map<string, number>();
  const depth = new Map<string, number>();
  let nextLeaf = 0;
  const place = (n: { id: string }, d: number): number => {
    depth.set(n.id, d);
    const kids = children(n.id);
    const cx = kids.length === 0
      ? x + width / 2 + nextLeaf++ * (width + gap)
      : kids.map((k) => place(k, d + 1)).reduce((a, b) => a + b, 0) / kids.length;
    centre.set(n.id, cx);
    return cx;
  };
  children(undefined).forEach((root) => place(root, 0));
  const has = (key: string, nodeId: string) => Array.isArray(e[key]) && (e[key] as string[]).includes(nodeId);
  const out: El[] = nodes.map((n) => ({
    type: "ellipse", id: `${id}/${n.id}`, x: (centre.get(n.id) ?? 0) - width / 2, y: y + (depth.get(n.id) ?? 0) * levelGap,
    width, height: size, strokeColor: INK, fillStyle: "solid",
    backgroundColor: has("answer", n.id) ? DSA.answer : has("current", n.id) ? DSA.current : DSA.cell,
    label: lbl(n.label, NOTE),
  }));
  for (const n of nodes) {
    if (n.parent === undefined) continue;
    out.push({ type: "arrow", start: { id: `${id}/${n.parent}` }, end: { id: `${id}/${n.id}` }, endArrowhead: null, strokeColor: INK });
  }
  return out;
}

/** Time down the left, one column per actor; bars for spans, marks for instants. */
function lanes(e: El): El[] {
  const x = num(e.x), y = num(e.y), laneWidth = num(e.laneWidth, 150), tickGap = num(e.tickGap, 48);
  const id = String(e.id);
  const laneNames = (Array.isArray(e.lanes) ? e.lanes : []).map(String);
  const ticks = (Array.isArray(e.ticks) ? e.ticks : []).map(String);
  const axis = 50, header = 40;
  const top = y + header;
  const laneX = (name: string) => {
    const i = laneNames.indexOf(name);
    if (i < 0) throw new Error(`x-lanes ${id}: unknown lane ${name}`);
    return x + axis + i * laneWidth;
  };
  const tickY = (at: unknown) => {
    const i = typeof at === "number" ? at : ticks.indexOf(String(at));
    if (i < 0) throw new Error(`x-lanes ${id}: unknown tick ${String(at)}`);
    return top + i * tickGap;
  };
  const bottom = top + Math.max(ticks.length - 1, 0) * tickGap;
  const out: El[] = [];
  ticks.forEach((t, i) => out.push(text(t, x, top + i * tickGap - SMALL / 2, SMALL, MUTED)));
  laneNames.forEach((name, i) => {
    const cx = x + axis + i * laneWidth + laneWidth / 2;
    out.push(centredText(name, cx, y, NOTE));
    out.push(line(cx, top, 0, bottom - top, MUTED, { strokeStyle: "dashed" }));
  });
  for (const b of (e.bars as El[] | undefined) ?? []) {
    const lx = laneX(String(b.lane));
    const y1 = tickY(b.from), y2 = tickY(b.to);
    const { fill, stroke } = KINDS[kindOf(b, "x-lanes bar")];
    const state = stateColour(b.state);
    out.push({ type: "rectangle", x: lx + laneWidth / 2 - 14, y: y1, width: 28, height: y2 - y1,
      backgroundColor: fill, strokeColor: state ?? stroke, fillStyle: b.state === "attention" ? "hachure" : "solid",
      strokeWidth: state ? 2 : 1 });
    if (b.label) out.push(text(String(b.label), lx + laneWidth / 2 + 20, y1, SMALL, INK));
  }
  for (const m of (e.marks as El[] | undefined) ?? []) {
    const lx = laneX(String(m.lane));
    const my = tickY(m.at);
    out.push(line(lx + laneWidth / 2 - 18, my, 36, 0, INK, { strokeWidth: 2 }));
    if (m.state === "failure") out.push(...cross(lx + laneWidth / 2 - 12, my - 12, 24));
    if (m.label) out.push(text(String(m.label), lx + laneWidth / 2 + 20, my - SMALL / 2, SMALL, stateColour(m.state) ?? INK));
  }
  return [...out, ...anchor(e.id, x, y, axis + laneNames.length * laneWidth, bottom - y)];
}

/** Degrees clockwise from 12 o'clock to a point on the circle. */
function onCircle(cx: number, cy: number, r: number, angle: number): [number, number] {
  const rad = (angle * Math.PI) / 180;
  return [cx + r * Math.sin(rad), cy - r * Math.cos(rad)];
}

/** A hash ring: the circle, tokens at angles, highlighted arcs. */
function ring(e: El): El[] {
  const cx = num(e.cx), cy = num(e.cy), r = num(e.r, 120), token = 28;
  const out: El[] = [{ type: "ellipse", x: cx - r, y: cy - r, width: 2 * r, height: 2 * r, strokeColor: INK, backgroundColor: "transparent" }];
  for (const a of (e.arcs as El[] | undefined) ?? []) {
    const from = num(a.from), to = num(a.to) < num(a.from) ? num(a.to) + 360 : num(a.to);
    const steps = Math.max(2, Math.ceil((to - from) / 10));
    const [sx, sy] = onCircle(cx, cy, r, from);
    const points = Array.from({ length: steps + 1 }, (_, i) => {
      const [px, py] = onCircle(cx, cy, r, from + ((to - from) * i) / steps);
      return [Math.round(px - sx), Math.round(py - sy)];
    });
    out.push({ type: "line", x: Math.round(sx), y: Math.round(sy), points, strokeWidth: 4,
      strokeColor: stateColour(a.state) ?? STATE.attention, roundness: { type: 2 } });
    if (a.label) {
      const [lx, ly] = onCircle(cx, cy, r + 34, (from + to) / 2);
      out.push(centredText(String(a.label), lx, Math.round(ly - SMALL / 2), SMALL, stateColour(a.state) ?? STATE.attention));
    }
  }
  for (const t of (e.tokens as El[] | undefined) ?? []) {
    const [tx, ty] = onCircle(cx, cy, r, num(t.angle));
    const { fill, stroke } = KINDS[kindOf(t, "x-ring token")];
    out.push({ type: "ellipse", ...(typeof t.id === "string" ? { id: t.id } : {}), x: Math.round(tx - token / 2),
      y: Math.round(ty - token / 2), width: token, height: token, backgroundColor: fill, strokeColor: stroke, fillStyle: "solid" });
    const [lx, ly] = onCircle(cx, cy, r + 34, num(t.angle));
    out.push(centredText(String(t.label ?? ""), lx, Math.round(ly - NOTE / 2), NOTE));
  }
  return [...out, ...anchor(e.id, cx - r, cy - r, 2 * r, 2 * r)];
}

/** A padlock: a body and a shackle. */
function lock(e: El): El[] {
  const x = num(e.x), y = num(e.y), s = num(e.size, 28);
  const shackle = [[0, 0], [0, -s * 0.35], [s * 0.15, -s * 0.6], [s * 0.45, -s * 0.6], [s * 0.6, -s * 0.35], [s * 0.6, 0]]
    .map(([px, py]) => [Math.round(px), Math.round(py)]);
  const out: El[] = [
    { type: "rectangle", x, y: y + s * 0.4, width: s, height: s * 0.6, strokeColor: INK, backgroundColor: KINDS.external.fill, fillStyle: "solid" },
    { type: "line", x: x + Math.round(s * 0.2), y: y + Math.round(s * 0.4), points: shackle, strokeColor: INK, roundness: { type: 2 } },
  ];
  if (e.label) out.push(text(String(e.label), x + s + 8, y + s * 0.4, SMALL));
  return out;
}

/** A cast member as a stick figure, with the role's name under it. */
function actor(e: El): El[] {
  const role = String(e.role ?? "");
  if (!(CAST as readonly string[]).includes(role)) throw new Error(`x-actor role ${role} is not in the cast`);
  const x = num(e.x), y = num(e.y), s = num(e.size, 60);
  const { fill, stroke } = KINDS.external;
  const head = Math.round(s * 0.3), cx = x + s / 2, neck = y + head, hip = y + s * 0.7;
  const name = String(e.label ?? role.replace(/-/g, " "));
  return [
    { type: "ellipse", x: cx - head / 2, y, width: head, height: head, strokeColor: stroke, backgroundColor: fill, fillStyle: "solid" },
    line(cx, neck, 0, hip - neck, stroke),
    line(cx - s * 0.3, neck + s * 0.15, s * 0.6, 0, stroke),
    line(cx, hip, -s * 0.25, s * 0.3, stroke),
    line(cx, hip, s * 0.25, s * 0.3, stroke),
    centredText(name, cx, y + s + 6, SMALL, stroke),
    ...anchor(e.id, x, y, s, s),
  ];
}
