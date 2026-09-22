import { useEffect, useState } from "react";

let nextId = 0;

// The house look for every diagram: navy on pale blue in light mode, Mermaid's own dark theme in
// dark mode.
const LIGHT = {
  primaryColor: "#eef2ff",
  primaryBorderColor: "#1e3a8a",
  primaryTextColor: "#0f172a",
  lineColor: "#1e3a8a",
  edgeLabelBackground: "#ffffff",
  ...scale(["#eef2ff", "#e0e7ff", "#dbeafe", "#e2e8f0", "#ede9fe", "#e0f2fe", "#ecfccb", "#fee2e2"], "#0f172a"),
};

// Mermaid's own dark theme is right for boxes and lines, but mindmaps and timelines colour their
// branches from a rainbow palette that fights the page and puts grey text on grey. Four quiet
// shades, one readable label colour.
const DARK = {
  // The mindmap's root takes the primary colours, and Mermaid's dark default puts grey on grey.
  primaryColor: "#243244",
  primaryBorderColor: "#64748b",
  primaryTextColor: "#e5e7eb",
  ...scale(["#1f2937", "#243244", "#2b3a2f", "#3a2f3f", "#263445", "#33302a", "#2a3b3b", "#3b2f34"], "#e5e7eb"),
};

/**
 * cScale0.. and cScaleLabel0.. are what mindmap, timeline and journey colour their branches from.
 * Mermaid cycles through twelve; past the ones named here it falls back to its own bright palette,
 * so eight are set — more branches than any readable diagram should have.
 */
function scale(fills: string[], label: string) {
  return Object.fromEntries(fills.flatMap((fill, i) => [
    [`cScale${i}`, fill],
    [`cScaleLabel${i}`, label],
    [`cScalePeer${i}`, label],
  ]));
}

/**
 * Settings shared by every diagram.
 *
 * - layout "elk", for flowcharts only: the ELK engine arranges them to cross fewer edges than the
 *   default engine. Mermaid 12's full build registers it by default; its "tiny" build does not, and
 *   then silently falls back, so do not switch builds without checking. Every other kind of diagram
 *   keeps Mermaid's own layout: a mindmap handed to ELK throws ("Cannot read properties of null")
 *   and the page shows its source instead of a picture.
 * - No fontFamily, on purpose. Mermaid sizes every box by measuring its text in the font the browser
 *   actually has; name one the browser lacks and the text overflows and clips on every label.
 * - securityLevel "strict": labels are sanitised, so a diagram can never carry script.
 */
export function diagramConfig(dark: boolean, chart = "flowchart") {
  return {
    startOnLoad: false,
    securityLevel: "strict" as const,
    layout: laidOutByElk(chart) ? "elk" : "dagre",
    theme: dark ? ("dark" as const) : ("base" as const),
    themeVariables: dark ? DARK : LIGHT,
  };
}

/** Only the two flowchart keywords; anything else draws itself. */
export function laidOutByElk(chart: string): boolean {
  const first = chart.trimStart().split(/\s/, 1)[0];
  return first === "flowchart" || first === "graph" || first === "flowchart-elk";
}

/**
 * Mermaid keeps one global configuration, so two diagrams drawing at once fight over it: a page with
 * a flowchart and a mindmap could hand the mindmap the flowchart's ELK layout, which throws. Every
 * draw therefore waits for the one before it, and sets the configuration it needs just before its
 * own render.
 */
let drawing: Promise<unknown> = Promise.resolve();

/**
 * Draws a Mermaid diagram. The library is large, so it is imported only when a page actually has a
 * diagram, and never lands in the main bundle.
 */
export function Mermaid({ chart }: { chart: string }) {
  const [svg, setSvg] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let live = true;
    const id = `diagram-${++nextId}`;
    const dark = window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false;
    drawing = drawing
      .catch(() => undefined)  // one diagram failing must not stop the next
      .then(() => import("mermaid"))
      .then(async ({ default: mermaid }) => {
        mermaid.initialize(diagramConfig(dark, chart));
        const { svg } = await mermaid.render(id, chart);
        if (live) setSvg(svg);
      });
    drawing
      .catch((e) => {
        // Named in the console: a diagram that parses in CI can still fail to draw here (a layout
        // engine the build does not carry), and the fallback below is otherwise silent.
        console.error("mermaid could not draw a diagram", e);
        if (live) setFailed(true);
      });
    return () => {
      live = false;
    };
  }, [chart]);

  // A diagram that will not draw still shows its source, rather than leaving a hole in the page.
  if (failed) return <pre className="diagram-source">{chart}</pre>;
  if (!svg) return <div className="diagram" aria-busy="true">Drawing the diagram…</div>;
  return <div className="diagram" role="img" dangerouslySetInnerHTML={{ __html: svg }} />;
}
