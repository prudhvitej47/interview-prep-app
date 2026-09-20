export type TopicSummary = {
  id: string;
  domainId: string;
  parentId: string | null;
  name: string;
};

export async function fetchTopics(): Promise<TopicSummary[]> {
  const response = await fetch("/api/topics");
  if (!response.ok) {
    throw new Error(`Could not load topics (${response.status})`);
  }
  return response.json();
}
