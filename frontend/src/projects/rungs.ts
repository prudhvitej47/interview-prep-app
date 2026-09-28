import type { Rung } from "../api";

/**
 * The angles an interviewer takes on a project, in the order a deep dive climbs them: what it is,
 * why it was built that way, what breaks at scale, what breaks when a part dies, what you would
 * change, and the behavioural story behind it.
 */
export const RUNG_LABEL: Record<Rung, string> = {
  walkthrough: "Walk-through",
  why: "Why this way",
  scale: "At scale",
  failure: "When it fails",
  change: "What you would change",
  story: "The story",
};
