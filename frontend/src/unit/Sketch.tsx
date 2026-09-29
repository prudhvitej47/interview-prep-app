import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { fitToReadable } from "./Mermaid";
import { useOverflow } from "./overflow";
// Excalifont's files and unicode-ranges, read from the pinned package by vite.config.ts.
import excalifontFaces from "virtual:excalifont";

declare global {
  interface Window {
    EXCALIDRAW_ASSET_PATH?: string | string[];
  }
}

/**
 * Where Excalidraw would fetch fonts itself, should it ever need to: our own copies, never its CDN.
 * Must agree with EXCALIFONT_URL in vite.config.ts.
 */
export const SKETCH_ASSET_PATH = "/assets/excalidraw/";

/** What an ```excalidraw fence holds: a name for screen readers, and Excalidraw element skeletons. */
export interface SketchSpec {
  title: string;
  elements: Record<string, unknown>[];
}

/**
 * Reads a fence. The elements are Excalidraw's "skeleton" form (a box with a label, an arrow from one
 * id to another), not a saved scene: the library fills in every other field when it draws, which
 * keeps a diagram small enough to review in a pull request.
 */
export function parseSketch(source: string): SketchSpec {
  const spec: unknown = JSON.parse(source);
  if (typeof spec !== "object" || spec === null) throw new Error("a sketch is a JSON object");
  const { title, elements } = spec as Partial<SketchSpec>;
  if (typeof title !== "string" || !title.trim()) throw new Error("a sketch needs a title");
  if (!Array.isArray(elements) || elements.length === 0) throw new Error("a sketch needs elements");
  return { title: title.trim(), elements };
}

let excalifontAdded = false;

/** Registers Excalifont on the page once. Nothing downloads until a sketch's text needs a file. */
function addExcalifont() {
  if (excalifontAdded || typeof FontFace === "undefined" || !document.fonts) return;
  for (const face of excalifontFaces) {
    document.fonts.add(new FontFace("Excalifont", `url("${face.url}") format("woff2")`,
      { unicodeRange: face.unicodeRange, display: "swap" }));
  }
  excalifontAdded = true;
}

type Box = { x: number; y: number; width: number; height: number };
type Point = { x: number; y: number };

/** The space left between an arrow's tip and the box it points at. */
const ARROW_GAP = 6;

/**
 * Excalidraw's converter records which boxes an arrow is bound to, but draws the arrow wherever its
 * own x, y and points put it: an arrow given only `start: {id}` and `end: {id}` comes out as a stub.
 * So an arrow that names both ends and has no points of its own is routed here, as a straight line
 * from the edge of one box to the edge of the other, aimed centre to centre. An author who wants a
 * bend gives the points and is left alone.
 */
export function routeArrows(elements: Record<string, unknown>[]): Record<string, unknown>[] {
  const boxes = new Map<string, Box>();
  for (const e of elements) {
    if (typeof e.id === "string" && isBox(e)) boxes.set(e.id, e);
  }
  return elements.map((e) => {
    if (e.type !== "arrow" || Array.isArray(e.points)) return e;
    const from = boxes.get((e.start as { id?: string } | undefined)?.id ?? "");
    const to = boxes.get((e.end as { id?: string } | undefined)?.id ?? "");
    if (!from || !to) return e;
    const a = edgePoint(from, centre(to));
    const b = edgePoint(to, centre(from));
    return { ...e, x: a.x, y: a.y, points: [[0, 0], [b.x - a.x, b.y - a.y]] };
  });
}

function isBox(e: Record<string, unknown>): e is Record<string, unknown> & Box {
  return ["x", "y", "width", "height"].every((k) => typeof e[k] === "number");
}

function centre(box: Box): Point {
  return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
}

/** Where the line from a box's centre towards `toward` leaves the box, pushed out by ARROW_GAP. */
export function edgePoint(box: Box, toward: Point): Point {
  const c = centre(box);
  const dx = toward.x - c.x;
  const dy = toward.y - c.y;
  if (dx === 0 && dy === 0) return c;
  // How far along the line the box's nearer vertical and horizontal edges are; the smaller one is hit.
  const tx = dx === 0 ? Infinity : (box.width / 2 + ARROW_GAP) / Math.abs(dx);
  const ty = dy === 0 ? Infinity : (box.height / 2 + ARROW_GAP) / Math.abs(dy);
  const t = Math.min(tx, ty);
  return { x: Math.round(c.x + dx * t), y: Math.round(c.y + dy * t) };
}

/**
 * Draws an ```excalidraw fence as a static hand-drawn SVG. Nothing on the page can edit or pan it:
 * only the library's converter and SVG exporter run, and the library is imported only when a page
 * has a sketch, so it never lands in the main bundle.
 */
export function Sketch({ source }: { source: string }) {
  const [drawn, setDrawn] = useState<{ svg: string; title: string } | null>(null);
  const [failed, setFailed] = useState(false);
  const box = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const svg = box.current?.querySelector("svg");
    if (svg) fitToReadable(svg);
  }, [drawn]);
  const scrolls = useOverflow(box, drawn);

  useEffect(() => {
    let live = true;
    const dark = window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false;
    (async () => {
      const spec = parseSketch(source);
      window.EXCALIDRAW_ASSET_PATH = SKETCH_ASSET_PATH;
      const { convertToExcalidrawElements, exportToSvg } = await import("@excalidraw/excalidraw");
      // The converter sizes every label by measuring it in Excalifont; measured in a fallback font
      // before the real one arrives, labels would be laid out for the wrong widths.
      addExcalifont();
      await document.fonts?.load('20px "Excalifont"');
      const elements = convertToExcalidrawElements(routeArrows(spec.elements) as never, { regenerateIds: false });
      // No background of its own, so the sketch sits on the page's diagram panel in either theme.
      // Dark mode is Excalidraw's own: it inverts the drawing and turns the hues back round.
      const svg = await exportToSvg({
        elements,
        appState: { exportBackground: false, exportWithDarkMode: dark },
        files: null,
        exportPadding: 12,
        // The page registers the font instead (addExcalifont), which keeps Excalidraw's
        // font-subsetting engine, about 735 kB, from ever being fetched.
        skipInliningFonts: true,
      });
      if (live) setDrawn({ svg: svg.outerHTML, title: spec.title });
    })().catch((e) => {
      console.error("excalidraw could not draw a sketch", e);
      if (live) setFailed(true);
    });
    return () => {
      live = false;
    };
  }, [source]);

  if (failed) return <pre className="diagram-source">{source}</pre>;
  if (!drawn) return <div className="diagram" aria-busy="true">Drawing the diagram…</div>;
  return (
    <div className="diagram sketch" role="figure" aria-label={scrolls ? `${drawn.title}, scrolls sideways` : drawn.title}
      tabIndex={scrolls ? 0 : undefined} ref={box} dangerouslySetInnerHTML={{ __html: drawn.svg }} />
  );
}
