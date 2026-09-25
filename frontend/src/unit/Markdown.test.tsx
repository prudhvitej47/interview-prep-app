import { render } from "@testing-library/react";
import { expect, it } from "vitest";
import { Markdown } from "./Markdown";

const show = (markdown: string) => {
  const { container } = render(<div className="unit"><Markdown>{markdown}</Markdown></div>);
  return container;
};

it("marks up a Java block so it is no longer a wall of text", () => {
  const container = show("```java\n// keeps the order\npublic int size() { return 1; }\n```");
  const code = container.querySelector("pre code")!;
  expect(code).toHaveClass("language-java");
  expect(code.querySelector(".hljs-comment")).toHaveTextContent("// keeps the order");
  expect(code.querySelector(".hljs-keyword")).toHaveTextContent("public");
  expect(code.querySelector(".hljs-number")).toHaveTextContent("1");
  // Every character of the source survives the round trip through hast.
  expect(code.textContent).toBe("// keeps the order\npublic int size() { return 1; }");
});

it("marks up the other languages the curriculum uses", () => {
  for (const [language, source, token] of [
    ["sql", "select id from orders where id = 7", ".hljs-keyword"],
    ["yaml", "spring:\n  datasource: postgres\n", ".hljs-attr"],
    ["properties", "server.port=8080\n", ".hljs-attr"],
  ] as const) {
    const container = show("```" + language + "\n" + source + "\n```");
    expect(container.querySelector(`pre code ${token}`), language).not.toBeNull();
  }
});

it("leaves a plain text block plain, because output has no grammar to show", () => {
  const container = show("```text\nCaused by: java.sql.SQLException\n```");
  const code = container.querySelector("pre code")!;
  expect(code).toHaveClass("language-text");
  expect(code.querySelector("span")).toBeNull();
  expect(code.textContent).toContain("Caused by: java.sql.SQLException");
});

it("still hands a mermaid fence to the diagram instead of the highlighter", () => {
  const container = show("```mermaid\nflowchart LR\n  a --> b\n```");
  expect(container.querySelector("pre")).toBeNull();
  expect(container.querySelector(".diagram")).toBeInTheDocument();
});

it("puts a table in its own scrolling box, so a wide one cannot push the page sideways", () => {
  const { container } = render(<Markdown>{"| a | b |\n| --- | --- |\n| 1 | 2 |\n"}</Markdown>);
  const table = container.querySelector("table");
  expect(table?.parentElement?.className).toBe("table-scroll");
  expect(table?.querySelectorAll("td")).toHaveLength(2);
});
