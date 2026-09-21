import { isValidElement, type ReactNode } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Mermaid } from "./Mermaid";

/**
 * Renders unit Markdown. A ```mermaid fence becomes a diagram; everything else is ordinary Markdown.
 * Raw HTML in the source is not rendered — react-markdown's default, and the right one.
 */
export function Markdown({ children }: { children: string }) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        code({ className, children: code, ...rest }) {
          if (className === "language-mermaid") return <Mermaid chart={String(code).trim()} />;
          return <code className={className} {...rest}>{code}</code>;
        },
        // A diagram replaces its code block entirely instead of sitting inside a <pre>.
        pre({ children: inner }) {
          return isMermaid(inner) ? <>{inner}</> : <pre>{inner}</pre>;
        },
      }}
    >
      {children}
    </ReactMarkdown>
  );
}

// react-markdown hands <pre> the element for its <code> child. That element's type is the code
// override above, not Mermaid — Mermaid is only what the override returns once rendered — so the
// fence is recognised by its language instead.
function isMermaid(node: ReactNode): boolean {
  return isValidElement<{ className?: string }>(node) && node.props.className === "language-mermaid";
}
