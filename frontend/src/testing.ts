import { vi } from "vitest";

type Route = { status?: number; body?: unknown };

/** Stubs fetch with canned responses per URL, and records every call for assertions. */
export function mockFetch(routes: Record<string, Route>) {
  // init is unused here, but declaring it types the recorded calls so tests can inspect it.
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const fetchMock = vi.fn(async (url: string, _init?: RequestInit) => {
    const route = routes[url];
    if (!route) throw new Error(`unexpected fetch ${url}`);
    const status = route.status ?? 200;
    return { ok: status >= 200 && status < 300, status, json: async () => route.body };
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

export const DOMAINS = [
  { id: "dsa", name: "DSA and coding", weight: 60, examples: ["Graphs", "Trees"] },
  { id: "databases", name: "Databases and SQL", weight: 40, examples: ["Indexes"] },
];

export const me = (overrides: object = {}) => ({
  slug: "tester",
  displayName: "Tester",
  onboarded: false,
  domainRatings: {},
  ...overrides,
});

export const topics = [
  { id: "ds.transactions", domainId: "distributed", parentId: null, name: "Transactions", unitCount: 1 },
  { id: "ds.transactions.sagas", domainId: "distributed", parentId: "ds.transactions", name: "Sagas", unitCount: 0 },
  { id: "hld.payments", domainId: "hld", parentId: null, name: "Payments", unitCount: 0 },
];

export const homeDomains = [
  { id: "distributed", name: "Distributed systems", weight: 60, examples: [] },
  { id: "hld", name: "High-level design", weight: 40, examples: [] },
];

export const unit = (overrides: object = {}) => ({
  id: "ds.transactions.idempotency-keys",
  title: "Idempotency keys for safe retries",
  type: "concept",
  difficulty: 3,
  estMinutes: 30,
  rounds: ["hld", "scenario"],
  technologies: ["postgresql"],
  origin: "synthesized",
  state: "draft",
  version: 1,
  markdown:
    "## Why it matters\nRetries can charge twice.\n\n## Diagram\n```mermaid\nsequenceDiagram\n  C->>S: pay\n```",
  topicId: "ds.transactions",
  topicName: "Transactions",
  domainId: "distributed",
  domainName: "Distributed systems",
  sources: [
    { kind: "book", title: "System Design Interview Vol. 2", url: null, locator: "Ch. 11" },
    { kind: "engineering-blog", title: "Stripe on idempotency", url: "https://stripe.com/blog/idempotency", locator: null },
  ],
  prerequisites: [],
  ...overrides,
});
