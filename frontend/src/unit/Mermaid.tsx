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
};

/**
 * Settings shared by every diagram.
 *
 * - layout "elk": the ELK engine arranges flowcharts to cross fewer edges than the default engine.
 *   Mermaid 12's full build registers it by default; its "tiny" build does not, and then silently
 *   falls back, so do not switch builds without checking. Sequence diagrams have their own layout
 *   and are unaffected.
 * - No fontFamily, on purpose. Mermaid sizes every box by measuring its text in the font the browser
 *   actually has; name one the browser lacks and the text overflows and clips on every label.
 * - securityLevel "strict": labels are sanitised, so a diagram can never carry script.
 */
export function diagramConfig(dark: boolean) {
  return {
    startOnLoad: false,
    securityLevel: "strict" as const,
    layout: "elk",
    theme: dark ? ("dark" as const) : ("base" as const),
    themeVariables: dark ? {} : LIGHT,
  };
}

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
    import("mermaid")
      .then(async ({ default: mermaid }) => {
        mermaid.initialize(diagramConfig(dark));
        const { svg } = await mermaid.render(id, chart);
        if (live) setSvg(svg);
      })
      .catch(() => live && setFailed(true));
    return () => {
      live = false;
    };
  }, [chart]);

  // A diagram that will not draw still shows its source, rather than leaving a hole in the page.
  if (failed) return <pre className="diagram-source">{chart}</pre>;
  if (!svg) return <div className="diagram" aria-busy="true">Drawing the diagram…</div>;
  return <div className="diagram" role="img" dangerouslySetInnerHTML={{ __html: svg }} />;
}
