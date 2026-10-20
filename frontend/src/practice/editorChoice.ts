export type EditorChoice = "plain" | "codemirror";

const KEY = "interview-prep.editor";

/** The learner's editor, plain unless they chose highlighting. Storage can be blocked: then plain. */
export function storedChoice(): EditorChoice {
  try {
    return localStorage.getItem(KEY) === "codemirror" ? "codemirror" : "plain";
  } catch {
    return "plain";
  }
}

export function storeChoice(choice: EditorChoice): void {
  try {
    localStorage.setItem(KEY, choice);
  } catch {
    // Blocked storage: the choice lasts for this page only.
  }
}
