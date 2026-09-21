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
