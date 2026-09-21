import { useEffect, useState } from "react";

let nextId = 0;

/**
 * Draws a Mermaid diagram. The library is large, so it is imported only when a page actually has a
 * diagram, and never lands in the main bundle.
 *
 * securityLevel "strict" makes Mermaid sanitise labels, so a diagram can never carry script. The
 * content is reviewed before it reaches the app anyway; this is the second lock.
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
        mermaid.initialize({ startOnLoad: false, securityLevel: "strict", theme: dark ? "dark" : "default" });
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
