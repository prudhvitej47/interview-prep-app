/**
 * The sketch palette. The source of truth is the content repository's COURSE_MAP.md section 4, and
 * DESIGN.md's Figures Rule is why sketches may use colour at all: one colour per kind of component, so
 * a reader can tell a datastore from a service before reading a label. The building blocks take every
 * colour from here; a sketch never types one. The content repository's sketch check keeps its own copy
 * of these values: change both together.
 */
export const INK = "#1e1e1e";
export const MUTED = "#868e96";

/** One fill and one stroke per kind of component. */
export const KINDS = {
  service: { fill: "#d0ebff", stroke: "#1971c2" },
  datastore: { fill: "#d3f9d8", stroke: "#2f9e44" },
  cache: { fill: "#c3fae8", stroke: "#0c8599" },
  queue: { fill: "#fff3bf", stroke: "#f08c00" },
  network: { fill: "#ffe8cc", stroke: "#e8590c" },
  coordination: { fill: "#e5dbff", stroke: "#6741d9" },
  model: { fill: "#ffdeeb", stroke: "#c2255c" },
  external: { fill: "#f1f3f5", stroke: "#495057" },
} as const;

export type Kind = keyof typeof KINDS;

/** State is drawn on top of a kind's colour, never as a fill. Red means failure and nothing else. */
export const STATE = {
  failure: "#e03131",
  ok: "#2f9e44",
  attention: "#f08c00",
} as const;

export type State = keyof typeof STATE;

/** Pale tints for zones whose tiers differ (public, private, isolated); components sit on top. */
export const ZONE_TINTS = ["#f8f9fa", "#e7f5ff", "#ebfbee"] as const;

/** Data-structure sketches show values, not components. */
export const DSA = {
  cell: "#ffffff",
  current: "#ffec99",
  pointer: "#1971c2",
  ruledOut: "#adb5bd",
  answer: "#b2f2bb",
} as const;

/** The people a sketch may draw (COURSE_MAP.md section 3). */
export const CAST = ["merchant", "customer", "support-agent", "on-call-engineer", "attacker", "developer"] as const;
