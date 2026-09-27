import { isValidElement, useRef, type ReactNode } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { createLowlight } from "lowlight";
import type { Element, Root, RootContent } from "hast";
import java from "highlight.js/lib/languages/java";
import sql from "highlight.js/lib/languages/sql";
import yaml from "highlight.js/lib/languages/yaml";
import properties from "highlight.js/lib/languages/properties";
import { Mermaid } from "./Mermaid";
import { useOverflow } from "./overflow";

/**
 * Only the languages the curriculum actually fences, registered one by one on highlight.js's bare
 * core: the whole library is 190 grammars and the app has to load over a private network with no
 * outside requests, so nothing ships that no unit uses. A ```text fence stays plain on purpose -
 * it holds output and prose, which has no grammar to show.
 */
const lowlight = createLowlight({ java, sql, yaml, properties });

/**
 * Renders unit Markdown. A ```mermaid fence becomes a diagram, a fence in a language we highlight
 * becomes marked-up code, and everything else is ordinary Markdown. Raw HTML in the source is not
 * rendered — react-markdown's default, and the right one.
 */
export function Markdown({ children }: { children: string }) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        code({ className, children: code, ...rest }) {
          const language = languageOf(className);
          if (language === "mermaid") return <Mermaid chart={String(code).trim()} />;
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
        // A diagram replaces its code block entirely instead of sitting inside a <pre>.
        pre({ children: inner }) {
          return isMermaid(inner) ? <>{inner}</> : <ScrollBox as="pre" label="Code">{inner}</ScrollBox>;
        },
      }}
    >
      {children}
    </ReactMarkdown>
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
// fence is recognised by its language instead.
function isMermaid(node: ReactNode): boolean {
  return isValidElement<{ className?: string }>(node) && node.props.className === "language-mermaid";
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
