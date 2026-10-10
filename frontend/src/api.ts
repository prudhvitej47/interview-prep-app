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

/**
 * A unit to learn or review, or (kind "project") a question about one of the learner's own projects:
 * then unitId is null, the title is the question's prompt and the project fields say where it lives.
 */
export type PlanItem = {
  unitId: string | null;
  title: string;
  type: string;
  day: number;
  kind: "learn" | "review" | "project";
  minutes: number;
  reason: string;
  done: boolean;
  projectQuestionId?: number | null;
  projectId?: number | null;
  projectName?: string | null;
  rung?: Rung | null;
};

export type PlanView = {
  plannedMinutes: number;
  goalMinutes: number;
  doneMinutes: number;
  items: PlanItem[];
  shares: { domainId: string; name: string; percent: number }[];
  notes: string[];
  topicsToRate: { topicId: string; name: string; domainName: string; currentGuess: number }[];
  /** Every item is done and there is more to learn that fits: the page offers extras. */
  canAddMore?: boolean;
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

/** Saving settings rebuilds this week's plan with them; this week's done items stay in it. */
export async function saveWeekSettings(settings: WeekSettings): Promise<Week> {
  await send("PUT", "/api/me/week", settings);
  await send("DELETE", "/api/plan");
  return fetchWeek();
}

/**
 * More learning on today, for a week whose plan is all done. It does not raise the week's planned
 * minutes or goal; done, it still counts.
 */
export async function addMore(minutes: 30 | 60 | 90): Promise<Week> {
  const response = await fetch("/api/plan/extra", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrfToken() },
    body: JSON.stringify({ minutes }),
  });
  if (response.ok) return response.json();
  // 400 and 409 say why in words, as the projects pages' refusals do.
  const why = response.status === 400 || response.status === 409
    ? ((await response.json()) as { problems?: string[] }).problems?.join(" ") : undefined;
  throw new Error(why || `Could not add more (${response.status})`);
}

/** Each area's share of the week with these weight overrides, exactly as the next plan would use it. */
/** An area's share: {@code unboostedPercent} is what the weights alone give, before the weakness factor. */
export type ShareDetail = { domainId: string; name: string; percent: number; unboostedPercent: number; strength: number };
export type SharePreview = ShareDetail[];

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

export type WeekOutcome = "GOAL_MET" | "BREAK" | "NOTHING_PLANNED" | "FREEZE_USED" | "MISSED" | "IN_PROGRESS";

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

/**
 * Where a learner stands with a unit or a project question, decided on the server in India time:
 * never attempted; attempted and due today or overdue; attempted, not due and next review under 16
 * days out; attempted, not due and next review 16 or more days out.
 */
export type Stage = "not-started" | "learned" | "review-due" | "solid";

/** How many units (or questions) are at each stage. */
export type StageCounts = { notStarted: number; learned: number; reviewDue: number; solid: number };

export type Dashboard = {
  coverage: { domainId: string; name: string; done: number; total: number; stages: StageCounts }[];
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

export type UnitProgress = {
  unitId: string;
  title: string;
  type: string;
  topicId: string;
  topicName: string;
  domainId: string;
  stage: Stage;
  /** Attempts after the first. */
  reviews: number;
  dueOn: string | null;
  /** Kept out of this learner's plans, by its own mark or a topic's above it. */
  notForMe: boolean;
};

export type QuestionProgress = {
  questionId: number;
  rung: Rung;
  prompt: string;
  stage: Stage;
  reviews: number;
  dueOn: string | null;
};

export type ProgressView = {
  today: string;
  /** Leaving out "not for me" units, which `notForMe` counts. */
  areas: { domainId: string; name: string; stages: StageCounts }[];
  units: UnitProgress[];
  notForMe: number;
  projects: { projectId: number; name: string; stages: StageCounts; questions: QuestionProgress[] }[];
};

export const fetchProgressView = () => getJson<ProgressView>("/api/progress");

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

/** The six angles an interviewer takes on a project, in ladder order. */
export type Rung = "walkthrough" | "why" | "scale" | "failure" | "change" | "story";

export type ProjectSummary = { id: number; name: string; questions: number; answered: number; due: number };

export type ProjectQuestion = {
  id: number;
  rung: Rung;
  prompt: string;
  probes: string;
  strongAnswer: string;
  units: { id: string; title: string }[];
  minutes: number;
  answer: string;
  answeredAt: string | null;
  /** The same shape as a unit's progress, so one rating panel serves both. */
  progress: Progress;
};

export type Project = {
  id: number;
  name: string;
  summary: string;
  topics: string[];
  questions: ProjectQuestion[];
  /** Hidden by the learner or by an import; listed so they can be restored. */
  hidden: { id: number; prompt: string }[];
};

export type ProjectImport = {
  projectsAdded: number;
  projectsUpdated: number;
  projectsRetired: number;
  questionsAdded: number;
  questionsUpdated: number;
  questionsRetired: number;
  unknownUnits: string[];
};

export type ProjectReviewQueue = {
  due: { questionId: number; projectId: number; projectName: string; rung: Rung; prompt: string; minutes: number; dueOn: string }[];
  nextDueOn: string | null;
};

export const fetchProjects = () => getJson<ProjectSummary[]>("/api/projects");
export const fetchProject = (id: number) => getOrNotFound<Project>(`/api/projects/${id}`);
export const fetchProjectReviews = () => getJson<ProjectReviewQueue>("/api/projects/reviews");

export async function saveAnswer(questionId: number, answer: string): Promise<{ answer: string; answeredAt: string | null }> {
  return (await send("PUT", `/api/projects/questions/${questionId}/answer`, { answer })).json();
}

export const rateQuestion = (questionId: number, rating: Rating) =>
  sendForProgress("POST", `/api/projects/questions/${questionId}/attempts`, { rating });
export const undoQuestionRating = (questionId: number) =>
  sendForProgress("DELETE", `/api/projects/questions/${questionId}/attempts/latest`);

/**
 * Sends the file exactly as it was read, so the server's 1 MB limit applies to what was chosen. A
 * refused file comes back as the list of what to fix.
 */
export async function importProjects(fileText: string): Promise<ProjectImport | { problems: string[] }> {
  const response = await fetch("/api/projects/import", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrfToken() },
    body: fileText,
  });
  if (response.ok || response.status === 422 || response.status === 413) return response.json();
  throw new Error(`Could not import it (${response.status}). Nothing was changed; try again.`);
}

export async function exportProjects(): Promise<Blob> {
  const response = await fetch("/api/projects/export");
  if (!response.ok) throw new Error(`Could not export (${response.status})`);
  return response.blob();
}

/** What a learner types to add or change a question. Unit ids not in the curriculum are dropped and reported. */
export type QuestionDraft = {
  rung: Rung;
  prompt: string;
  probes: string;
  strongAnswer: string;
  units: string[];
  minutes: number;
};

export type Saved = { id: number; unknownUnits: string[] };

/** Saved, or (422, or 409 for a stale reorder) the list of what to fix. */
async function sendChecked(method: string, url: string, body?: object): Promise<Saved | { problems: string[] }> {
  const response = await fetch(url, {
    method,
    headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrfToken() },
    body: body && JSON.stringify(body),
  });
  if (response.status === 422 || response.status === 409) return response.json();
  if (!response.ok) throw new Error(`Could not save that (${response.status})`);
  return response.status === 204 ? { id: 0, unknownUnits: [] } : response.json();
}

export const addProject = (project: { name: string; summary: string }) =>
  sendChecked("POST", "/api/projects", project);
export const changeProject = (id: number, project: { name: string; summary: string }) =>
  sendChecked("PUT", `/api/projects/${id}`, project);
export const hideProject = (id: number) => send("DELETE", `/api/projects/${id}`);
export const restoreProject = (id: number) => send("POST", `/api/projects/${id}/restore`);
export const fetchHiddenProjects = () => getJson<{ id: number; name: string }[]>("/api/projects/hidden");

export const addQuestion = (projectId: number, question: QuestionDraft) =>
  sendChecked("POST", `/api/projects/${projectId}/questions`, question);
export const changeQuestion = (id: number, question: QuestionDraft) =>
  sendChecked("PUT", `/api/projects/questions/${id}`, question);
export const hideQuestion = (id: number) => send("DELETE", `/api/projects/questions/${id}`);
export const restoreQuestion = (id: number) => send("POST", `/api/projects/questions/${id}/restore`);
export const reorderQuestions = (projectId: number, questionIds: number[]) =>
  sendChecked("PUT", `/api/projects/${projectId}/order`, { questionIds });
