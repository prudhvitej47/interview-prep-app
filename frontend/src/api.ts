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
  /** Closed "Start here" guides stay closed on every device; the server remembers. */
  startGuideClosed: boolean;
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

export type WeekSettings = { hoursPerWeek: number | null; studyDays: number[]; weights: Record<string, number> };

export type PlanItem = {
  unitId: string;
  title: string;
  type: string;
  day: number;
  kind: "learn" | "review";
  minutes: number;
  reason: string;
  done: boolean;
};

export type PlanView = {
  plannedMinutes: number;
  goalMinutes: number;
  doneMinutes: number;
  items: PlanItem[];
  shares: { domainId: string; name: string; percent: number }[];
  notes: string[];
  topicsToRate: { topicId: string; name: string; domainName: string; currentGuess: number }[];
};

export type Week = { weekStart: string; settings: WeekSettings; plan: PlanView | null; onBreak: boolean };

export const fetchWeek = () => getJson<Week>("/api/plan");

async function send(method: string, url: string, body?: object): Promise<Response> {
  const response = await fetch(url, {
    method,
    headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrfToken() },
    body: body && JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`Could not save that (${response.status})`);
  return response;
}

/** Saving settings rebuilds this week's plan with them; what is done stays done. */
export async function saveWeekSettings(settings: WeekSettings): Promise<Week> {
  await send("PUT", "/api/me/week", settings);
  await send("DELETE", "/api/plan");
  return fetchWeek();
}

/** Each area's share of the week with these weight overrides, exactly as the next plan would use it. */
export type SharePreview = { domainId: string; name: string; percent: number }[];

export async function previewShares(weights: Record<string, number>): Promise<SharePreview> {
  return (await send("POST", "/api/plan/shares", { weights })).json();
}

/** Closes the home page's "Start here" guide, or brings it back. */
export async function setStartGuideClosed(closed: boolean): Promise<Me> {
  return (await send("PUT", "/api/me/start-guide", { closed })).json();
}

/** Topics and units this learner has kept out of their own plans. */
export type NotForMe = { topics: string[]; units: string[] };

export const fetchNotForMe = () => getJson<NotForMe>("/api/me/not-for-me");

export async function setNotForMe(scope: "topic" | "unit", id: string, excluded: boolean): Promise<NotForMe> {
  return (await send("PUT", "/api/me/not-for-me", { scope, id, excluded })).json();
}

export async function rateTopics(ratings: Record<string, number>): Promise<void> {
  await send("PUT", "/api/me/ratings/topics", { ratings });
}

export type WeekOutcome = "GOAL_MET" | "BREAK" | "FREEZE_USED" | "MISSED" | "IN_PROGRESS";

export type Rewards = {
  stars: number;
  starsThisWeek: number;
  streak: number;
  longestStreak: number;
  freezes: number;
  thisWeekCounts: boolean;
  recentWeeks: { weekStart: string; outcome: WeekOutcome }[];
  milestonesReached: number[];
  nextMilestone: number | null;
};

export type Breaks = { thisWeek: boolean; upcoming: { weekStart: string; taken: boolean; available: boolean }[] };

export const fetchRewards = () => getJson<Rewards>("/api/rewards");
export const fetchBreaks = () => getJson<Breaks>("/api/breaks");
export const takeBreak = async (weekStart: string): Promise<Breaks> =>
  (await send("POST", "/api/breaks", { weekStart })).json();
export const giveBackBreak = async (weekStart: string): Promise<Breaks> =>
  (await send("DELETE", `/api/breaks/${weekStart}`)).json();

export type Placement = "now" | "next-week" | "end-of-track";

export type Dashboard = {
  coverage: { domainId: string; name: string; done: number; total: number }[];
  weakAreas: { topicId: string; name: string; domainName: string; strength: number; unitsLeft: number }[];
  whatChanged: {
    version: string;
    releasedAt: string;
    changelog: string | null;
    added: number;
    changed: number;
    retired: number;
    units: { unitId: string; title: string; type: string; added: boolean; placement: Placement; suggested: Placement; chosen: boolean }[];
  } | null;
};

export const fetchDashboard = () => getJson<Dashboard>("/api/dashboard");
export const placeUnit = async (unitId: string, choice: Placement): Promise<Dashboard> =>
  (await send("PUT", "/api/placements", { unitId, choice })).json();

export type EvidenceSummary = {
  id: string;
  companyId: string;
  company: string;
  role: string | null;
  level: string | null;
  location: string | null;
  interviewDate: string | null;
  sourceKind: string;
  sourceTitle: string;
  publisher: string | null;
  tier: string;
  outcome: string | null;
  rounds: number;
  questions: number;
  topics: string[];
};

export type EvidenceDetail = {
  summary: EvidenceSummary;
  sourceUrl: string | null;
  accessed: string;
  notes: string | null;
  rounds: { type: string; name: string; summary: string | null; questions: { text: string; topics: { id: string; name: string }[] }[] }[];
};

export type Option = { id: string; name: string };

export type DraftRound = { type: string; summary?: string; questions?: { text: string; topics: string[] }[] };

export type DraftBody = {
  company?: string;
  role?: string;
  level?: string;
  location?: string;
  interview_date?: string;
  outcome?: string;
  rounds?: DraftRound[];
  private_notes?: string;
};

export type Draft = {
  id: number;
  kind: "debrief";
  body: DraftBody;
  createdAt: string;
  updatedAt: string;
  sentAt: string | null;
  sentBranch: string | null;
  sentUrl: string | null;
};

export type SendResult = { branch: string; url: string } | { problems: string[] } | { notSetUp: true };

/** 422 lists what to fix; 503 means the server has no GitHub token, so the file can be downloaded instead. */
async function sendTo(url: string, body?: object): Promise<SendResult> {
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrfToken() },
    body: JSON.stringify(body ?? {}),
  });
  if (response.status === 503) return { notSetUp: true };
  if (response.ok || response.status === 422) return response.json();
  throw new Error(`Could not send it (${response.status}). Nothing was sent; try again.`);
}

export const sendDraft = (id: number) => sendTo(`/api/evidence/drafts/${id}/send`);
export const sendArticle = (article: { title: string; url: string; company: string; text: string }) =>
  sendTo("/api/inbox/articles", article);

export const fetchEvidence = () => getJson<EvidenceSummary[]>("/api/evidence");
export const fetchEvidenceDetail = (id: string) =>
  getOrNotFound<EvidenceDetail>(`/api/evidence/${encodeURIComponent(id)}`);
export const fetchEvidenceOptions = () => getJson<{ companies: Option[]; rounds: Option[] }>("/api/evidence/options");
export const fetchDrafts = () => getJson<Draft[]>("/api/evidence/drafts");
export const fetchDraft = (id: number) => getOrNotFound<Draft>(`/api/evidence/drafts/${id}`);

export async function saveDraft(draft: { id?: number; kind: string; body: DraftBody }): Promise<Draft> {
  const response = draft.id
    ? await send("PUT", `/api/evidence/drafts/${draft.id}`, { kind: draft.kind, body: draft.body })
    : await send("POST", "/api/evidence/drafts", { kind: draft.kind, body: draft.body });
  return response.json();
}

export const deleteDraft = (id: number) => send("DELETE", `/api/evidence/drafts/${id}`);

/** The evidence file, or the list of things to fix first. */
export async function exportDraft(id: number): Promise<{ path: string; yaml: string } | { problems: string[] }> {
  const response = await fetch(`/api/evidence/drafts/${id}/export`);
  if (response.ok || response.status === 422) return response.json();
  throw new Error(`Could not export (${response.status})`);
}
