import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { expect, it } from "vitest";
import { Markdown } from "./Markdown";
import type { Reference } from "../api";

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

it("hands an excalidraw fence to the sketch instead of showing it as code", () => {
  const container = show('```excalidraw\n{"title": "t", "elements": [{"type": "rectangle", "x": 0, "y": 0}]}\n```');
  expect(container.querySelector("pre")).toBeNull();
  expect(container.querySelector(".diagram")).toBeInTheDocument();
});

it("puts a table in its own scrolling box, so a wide one cannot push the page sideways", () => {
  const { container } = render(<Markdown>{"| a | b |\n| --- | --- |\n| 1 | 2 |\n"}</Markdown>);
  const table = container.querySelector("table");
  expect(table?.parentElement?.className).toBe("table-scroll");
  expect(table?.querySelectorAll("td")).toHaveLength(2);
});

it("lets a keyboard reach a code block or table only when it scrolls sideways", () => {
  // jsdom has no layout: every box measures 0 wide. Make every <pre> wider inside than out.
  const scrollWidth = Object.getOwnPropertyDescriptor(Element.prototype, "scrollWidth")!;
  Object.defineProperty(Element.prototype, "scrollWidth", {
    configurable: true,
    get() { return this.tagName === "PRE" ? 900 : 0; },
  });
  try {
    const container = show("```text\na very long line\n```\n\n| a | b |\n| - | - |\n| 1 | 2 |\n");
    const pre = container.querySelector("pre")!;
    expect(pre).toHaveAttribute("tabindex", "0");
    expect(pre).toHaveAttribute("role", "region");
    expect(pre).toHaveAccessibleName("Code, scrolls sideways");
    // The table fits, so it adds no Tab stop.
    expect(container.querySelector(".table-scroll")).not.toHaveAttribute("tabindex");
  } finally {
    Object.defineProperty(Element.prototype, "scrollWidth", scrollWidth);
  }
});

const references: Reference[] = [
  { id: "ds.transactions.idempotency-keys", kind: "unit", title: "Idempotency keys for safe retries",
    domainName: "Distributed systems", done: true },
  { id: "hld.classic.distributed-rate-limiter", kind: "unit", title: "Distributed rate limiter",
    domainName: "High-level design", done: false },
  { id: "ds.transactions", kind: "topic", title: "Transactions", domainName: "Distributed systems", done: false },
];

const linked = (markdown: string, refs?: Reference[]) =>
  render(<MemoryRouter><div className="unit"><Markdown references={refs}>{markdown}</Markdown></div></MemoryRouter>)
    .container;

it("shows a unit or topic named by id as a link that reads as its title", () => {
  const container = linked(
    "Idempotency: `ds.transactions.idempotency-keys`. Quotas: `hld.classic.distributed-rate-limiter`."
      + " More in `ds.transactions`.",
    references,
  );
  const unit = screen.getByRole("link", { name: "Idempotency keys for safe retries" });
  expect(unit).toHaveAttribute("href", "/units/ds.transactions.idempotency-keys");
  // The id and the domain are one hover away.
  expect(unit).toHaveAttribute("title", "ds.transactions.idempotency-keys · Distributed systems");
  expect(screen.getByRole("link", { name: "Transactions" })).toHaveAttribute("href", "/topics/ds.transactions");
  expect(container.querySelector("code")).toBeNull();
  // Only the done unit carries the tick, and it is outside the link so the link's name stays the title.
  expect(screen.getAllByRole("img", { name: "done" })).toHaveLength(1);
  expect(unit.nextElementSibling).toHaveTextContent("✓");
  expect(screen.getByRole("link", { name: "Distributed rate limiter" }).nextElementSibling).not.toHaveClass("reference-done");
});

it("leaves code that names nothing known as plain code", () => {
  const container = linked("Set `max.poll.interval.ms`, not `ds.transactions.retired-one`.", references);
  expect(screen.queryByRole("link")).toBeNull();
  expect([...container.querySelectorAll("code")].map((c) => c.textContent))
    .toEqual(["max.poll.interval.ms", "ds.transactions.retired-one"]);
});

it("never turns a code block into links, even when a line is an id", () => {
  const container = linked("```\nds.transactions.idempotency-keys\n```\n\n```text\nds.transactions\n```", references);
  expect(screen.queryByRole("link")).toBeNull();
  expect(container.querySelectorAll("pre code")).toHaveLength(2);
});

it("shows ids as plain code where there are no references, as in a note or a project page", () => {
  const container = linked("See `ds.transactions.idempotency-keys`.");
  expect(screen.queryByRole("link")).toBeNull();
  expect(container.querySelector("code")).toHaveTextContent("ds.transactions.idempotency-keys");
});

it("turns a predict fence into an exercise, not a code block", () => {
  const body = JSON.stringify({ question: "How many?", number: 5, reveal: "Five, **every** time." });
  const container = show("Before.\n\n```predict\n" + body + "\n```\n\nAfter.");
  expect(container.querySelector("pre")).toBeNull();
  expect(screen.getByRole("region", { name: "Predict" })).toBeInTheDocument();
  fireEvent.change(screen.getByRole("textbox", { name: "Your answer" }), { target: { value: "5" } });
  fireEvent.click(screen.getByRole("button", { name: "Check" }));
  // The reveal is Markdown, rendered by the unit's own renderer.
  expect(container.querySelector(".predict strong")).toHaveTextContent("every");
  expect(screen.getByText("After.")).toBeInTheDocument();
});

it("turns an evidence fence into an exercise", () => {
  const body = JSON.stringify({ prompt: "Which?", lines: ["a", "b"], cause: [1], explain: "Because." });
  const container = show("```evidence\n" + body + "\n```");
  expect(container.querySelector("pre")).toBeNull();
  expect(screen.getByRole("region", { name: "Read the evidence" })).toBeInTheDocument();
});

it("keeps the rest of the unit when an exercise fence is broken", () => {
  show("Intro.\n\n```predict\n{ not json\n```\n\nOutro.");
  expect(screen.getByText(/This exercise could not be read/)).toBeInTheDocument();
  expect(screen.getByText("Outro.")).toBeInTheDocument();
});

it("keeps an exercise's answer when the page re-renders", () => {
  const body = JSON.stringify({ question: "How many?", number: 5, reveal: "Five." });
  const source = "```predict\n" + body + "\n```";
  const { rerender } = render(<div className="unit"><Markdown>{source}</Markdown></div>);
  fireEvent.change(screen.getByRole("textbox", { name: "Your answer" }), { target: { value: "5" } });
  fireEvent.click(screen.getByRole("button", { name: "Check" }));
  rerender(<div className="unit"><Markdown>{source}</Markdown></div>);
  expect(screen.getByRole("status")).toHaveTextContent("Right.");
});
