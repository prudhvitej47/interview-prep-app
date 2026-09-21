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

function isMermaid(node: ReactNode): boolean {
  return isValidElement(node) && node.type === Mermaid;
}
