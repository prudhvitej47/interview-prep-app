export type TopicSummary = {
  id: string;
  domainId: string;
  parentId: string | null;
  name: string;
  unitCount: number;
};

export type Me = {
  slug: string;
  displayName: string;
  onboarded: boolean;
  domainRatings: Record<string, number>;
};

export type Domain = {
  id: string;
  name: string;
  weight: number;
  examples: string[];
};

/** The server knows who you are (via Tailscale) and you are not on its list. */
export class NotAllowedError extends Error {}

async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url);
  if (response.status === 403) {
    throw new NotAllowedError(url);
  }
  if (!response.ok) {
    throw new Error(`Could not load ${url} (${response.status})`);
  }
  return response.json();
}

export const fetchMe = () => getJson<Me>("/api/me");
export const fetchDomains = () => getJson<Domain[]>("/api/domains");
export const fetchTopics = () => getJson<TopicSummary[]>("/api/topics");

/**
 * The CSRF token the server set as a cookie on an earlier GET. Echoing it in a header proves the
 * request came from this page: another site's page can make the browser send the cookie, but
 * cannot read it to copy into the header.
 */
function csrfToken(): string {
  const cookie = document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN="));
  return cookie ? decodeURIComponent(cookie.slice("XSRF-TOKEN=".length)) : "";
}

export async function saveDomainRatings(ratings: Record<string, number>): Promise<Me> {
  const response = await fetch("/api/me/ratings/domains", {
    method: "PUT",
    headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrfToken() },
    body: JSON.stringify({ ratings }),
  });
  if (!response.ok) {
    throw new Error(`Could not save your ratings (${response.status})`);
  }
  return response.json();
}

export type UnitSummary = {
  id: string;
  title: string;
  type: string;
  difficulty: number;
  estMinutes: number;
};

export type TopicDetail = {
  id: string;
  name: string;
  domainId: string;
  domainName: string;
  parentId: string | null;
  parentName: string | null;
  subtopics: TopicSummary[];
  units: UnitSummary[];
};

export type UnitDetail = {
  id: string;
  title: string;
  type: string;
  difficulty: number;
  estMinutes: number;
  rounds: string[];
  technologies: string[];
  origin: string;
  state: string;
  version: number;
  markdown: string;
  topicId: string;
  topicName: string;
  domainId: string;
  domainName: string;
  sources: { kind: string; title: string; url: string | null; locator: string | null }[];
  prerequisites: { id: string; title: string }[];
  testCases: TestCase[];
  hiddenTestCases: number;
  sqlFixture: SqlFixture | null;
};

export type TestCase = { name: string; input: string; expected: string };

export type SqlFixture = { schema: string; seed: string; reference: string; orderMatters: boolean };

export type Note = { body: string; updatedAt: string | null };

export class NotFoundError extends Error {}

async function getOrNotFound<T>(url: string): Promise<T> {
  const response = await fetch(url);
  if (response.status === 404) throw new NotFoundError(url);
  if (!response.ok) throw new Error(`Could not load ${url} (${response.status})`);
  return response.json();
}

export const fetchTopic = (id: string) => getOrNotFound<TopicDetail>(`/api/topics/${encodeURIComponent(id)}`);
export const fetchUnit = (id: string) => getOrNotFound<UnitDetail>(`/api/units/${encodeURIComponent(id)}`);
export const fetchNote = (unitId: string) => getJson<Note>(`/api/units/${encodeURIComponent(unitId)}/note`);

export async function saveNote(unitId: string, body: string): Promise<Note> {
  const response = await fetch(`/api/units/${encodeURIComponent(unitId)}/note`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrfToken() },
    body: JSON.stringify({ body }),
  });
  if (!response.ok) throw new Error(`Could not save your note (${response.status})`);
  return response.json();
}

export type Rating = "again" | "hard" | "good" | "easy";

export type Progress = {
  attempts: number;
  firstDoneAt: string | null;
  lastAt: string | null;
  lastRating: Rating | null;
  dueOn: string | null;
  reviewDue: boolean;
};

export type ReviewQueue = {
  due: { unitId: string; title: string; type: string; estMinutes: number; dueOn: string }[];
  nextDueOn: string | null;
};

export const fetchProgress = (unitId: string) =>
  getJson<Progress>(`/api/units/${encodeURIComponent(unitId)}/progress`);
export const fetchReviews = () => getJson<ReviewQueue>("/api/reviews");

async function sendForProgress(method: string, url: string, body?: object): Promise<Progress> {
  const response = await fetch(url, {
    method,
    headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrfToken() },
    body: body && JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`Could not save that (${response.status})`);
  return response.json();
}

export const recordAttempt = (unitId: string, rating: Rating) =>
  sendForProgress("POST", `/api/units/${encodeURIComponent(unitId)}/attempts`, { rating });
export const undoAttempt = (unitId: string) =>
  sendForProgress("DELETE", `/api/units/${encodeURIComponent(unitId)}/attempts/latest`);
