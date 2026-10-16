import { isValidElement, useMemo, useRef, type ReactNode } from "react";
import { Link } from "react-router";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import { createLowlight } from "lowlight";
import type { Element, Root, RootContent } from "hast";
import java from "highlight.js/lib/languages/java";
import sql from "highlight.js/lib/languages/sql";
import yaml from "highlight.js/lib/languages/yaml";
import properties from "highlight.js/lib/languages/properties";
import { Mermaid } from "./Mermaid";
import { Sketch } from "./Sketch";
import { Predict } from "./Predict";
import { Evidence } from "./Evidence";
import { useOverflow } from "./overflow";
import type { Reference } from "../api";

/**
 * Only the languages the curriculum actually fences, registered one by one on highlight.js's bare
 * core: the whole library is 190 grammars and the app has to load over a private network with no
 * outside requests, so nothing ships that no unit uses. A ```text fence stays plain on purpose -
 * it holds output and prose, which has no grammar to show.
 */
const lowlight = createLowlight({ java, sql, yaml, properties });

/**
 * Renders unit Markdown. A ```mermaid fence becomes a diagram, an ```excalidraw fence a hand-drawn
 * sketch, a ```predict or ```evidence fence an exercise (practicals.ts), a fence in a language we highlight
 * becomes marked-up code, and everything else is ordinary Markdown. Raw HTML in the source is not
 * rendered — react-markdown's default, and the right one.
 *
 * Units name other units and topics by id in inline code (`ds.transactions.idempotency-keys`), which
 * keeps them checkable in the content repository. Given the unit's `references`, such a span
 * becomes a link that reads as the title; anywhere without references (notes, project pages) and
 * any code that is not a known id stays plain code.
 */
export function Markdown({ children, references }: { children: string; references?: Reference[] }) {
  const byId = useMemo(() => new Map(references?.map((r) => [r.id, r])), [references]);
  // Built once per set of references, not on every render: a new `code` function each time would be a new
  // component type to React, which would remount every exercise and wipe the learner's answer.
  const components = useMemo<Components>(() => ({
    code({ className, children: code, ...rest }) {
      const language = languageOf(className);
      // Only inline code: a fence's text ends in a newline, so it never equals an id anyway.
      const reference = language === null ? byId.get(String(code)) : undefined;
      if (reference) return <ReferenceLink reference={reference} />;
      if (language === "mermaid") return <Mermaid chart={String(code).trim()} />;
      if (language === "excalidraw") return <Sketch source={String(code).trim()} />;
      if (language === "predict") return <Predict source={String(code).trim()} render={renderNested} />;
      if (language === "evidence") return <Evidence source={String(code).trim()} render={renderNested} />;
      if (language && lowlight.registered(language)) {
        // Rendered as elements rather than a string of HTML: nothing here ever sets innerHTML.
        const tree = lowlight.highlight(language, String(code).replace(/\n$/, ""));
        return <code className={className} {...rest}>{toReact(tree)}</code>;
      }
      return <code className={className} {...rest}>{code}</code>;
    },
    // A wide table (a SQL result with seven snake_case columns) scrolls in its own box, like a
    // diagram, instead of pushing the whole page sideways.
    table({ children: rows, node: _node, ...rest }) {
      return <ScrollBox as="div" className="table-scroll" label="Table"><table {...rest}>{rows}</table></ScrollBox>;
    },
    // A diagram, sketch or exercise replaces its code block entirely instead of sitting inside a <pre>.
    pre({ children: inner }) {
      return isBlock(inner) ? <>{inner}</> : <ScrollBox as="pre" label="Code">{inner}</ScrollBox>;
    },
  }), [byId]);
  return (
    <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
      {children}
    </ReactMarkdown>
  );
}

/** Markdown inside an exercise (its question, reveal or explanation): same renderer, no id links. */
function renderNested(markdown: string): ReactNode {
  return <Markdown>{markdown}</Markdown>;
}

/**
 * A unit or topic named in the text, shown by its title. The id and domain stay one hover away, and
 * a unit already done carries the same muted tick as a finished step on Start here. The tick sits
 * outside the link, so the link's name is the title alone, and a no-break space keeps it from
 * wrapping onto a line by itself.
 */
function ReferenceLink({ reference: r }: { reference: Reference }) {
  return (
    <>
      <Link to={r.kind === "unit" ? `/units/${r.id}` : `/topics/${r.id}`} className="reference"
        title={`${r.id} · ${r.domainName}`}>{r.title}</Link>
      {r.done && <span className="reference-done">{"\u00a0"}<span role="img" aria-label="done">✓</span></span>}
    </>
  );
}

/**
 * A box that scrolls sideways - a long code line, a wide table - is out of reach of a keyboard
 * unless it can take focus: Safari never lets the arrow keys into it. So while, and only while, it
 * actually overflows, it joins the Tab order as a named region; a box that fits adds no stop.
 */
export function ScrollBox({ as: Tag, className, label, children }: {
  as: "div" | "pre";
  className?: string;
  label: string;
  children: ReactNode;
}) {
  const box = useRef<HTMLDivElement & HTMLPreElement>(null);
  const overflows = useOverflow(box);
  return (
    <Tag ref={box} className={className}
      {...(overflows ? { tabIndex: 0, role: "region", "aria-label": `${label}, scrolls sideways` } : {})}>
      {children}
    </Tag>
  );
}

function languageOf(className: string | undefined): string | null {
  return /^language-([\w+-]+)$/.exec(className ?? "")?.[1]?.toLowerCase() ?? null;
}

// react-markdown hands <pre> the element for its <code> child. That element's type is the code
// override above, not Mermaid — Mermaid is only what the override returns once rendered — so the
// fence is recognised by its language instead. Diagrams, sketches and exercises replace the <pre>.
const BLOCK_FENCES = new Set(["language-mermaid", "language-excalidraw", "language-predict", "language-evidence"]);
function isBlock(node: ReactNode): boolean {
  if (!isValidElement<{ className?: string }>(node)) return false;
  return BLOCK_FENCES.has(node.props.className ?? "");
}

/** lowlight returns a hast tree of nested spans; this turns it into React elements. */
function toReact(node: Root | RootContent, key?: number): ReactNode {
  if (node.type === "text") return node.value;
  if (node.type === "root") return node.children.map((child, i) => toReact(child, i));
  if (node.type !== "element") return null;
  const element = node as Element;
  const classes = element.properties?.className;
  return (
    <span key={key} className={Array.isArray(classes) ? classes.join(" ") : undefined}>
      {element.children.map((child, i) => toReact(child, i))}
    </span>
  );
}
