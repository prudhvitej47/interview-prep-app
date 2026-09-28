import { useEffect, useState } from "react";
import { Link, useLocation, useParams } from "react-router";
import {
  fetchProject,
  NotFoundError,
  rateQuestion,
  undoQuestionRating,
  type Project,
  type ProjectQuestion,
} from "../api";
import { AnswerBox } from "../projects/AnswerBox";
import { RUNG_LABEL } from "../projects/rungs";
import { Markdown } from "../unit/Markdown";
import { RatingPanel, type RatingWords } from "../unit/ProgressPanel";

const QUESTION_WORDS: RatingWords = {
  label: "How the rehearsal went",
  notDone: "Rehearsed it?",
  askFirst: "Say how your answer went. This schedules when the question comes back.",
  doneOn: "First rehearsed",
  undoFirst: "Undo: not rehearsed after all",
};

const HOW_TO_REHEARSE = "Answer it again out loud, from memory, then compare with what you wrote.";

/**
 * The follow-ups and the strong answer would spoil the rehearsal, like a coding unit's solution. They
 * stay out of reach until the learner has put down an answer of their own, or rated the question
 * (which is also the way through when they genuinely have no answer yet: rate it Again).
 */
export const canUnfold = (q: Pick<ProjectQuestion, "answer" | "progress">) =>
  q.answer.trim() !== "" || q.progress.attempts > 0;

export function ProjectPage() {
  const { projectId = "" } = useParams();
  const [project, setProject] = useState<Project | null>(null);
  const [error, setError] = useState<string | null>(null);
  const location = useLocation();

  useEffect(() => {
    setProject(null);
    setError(null);
    if (!/^\d+$/.test(projectId)) {
      setError("There is no such project.");
      return;
    }
    fetchProject(Number(projectId))
      .then(setProject)
      .catch((e: Error) => setError(e instanceof NotFoundError ? "There is no such project." : e.message));
  }, [projectId]);

  // A due question on the home page links here as #q-<id>; the questions arrive after the page does,
  // so the browser's own jump has nothing to land on. Scroll once they are drawn, as Home does.
  const loaded = project !== null;
  useEffect(() => {
    if (loaded && location.hash.startsWith("#q-")) {
      document.getElementById(location.hash.slice(1))?.scrollIntoView?.({ block: "start" });
    }
  }, [loaded, location.hash]);

  if (error) return <p role="alert">{error}</p>;
  if (!project) return <p>Loading…</p>;

  const change = (id: number, update: Partial<ProjectQuestion>) =>
    setProject((p) => p && { ...p, questions: p.questions.map((q) => (q.id === id ? { ...q, ...update } : q)) });
  const minutes = project.questions.reduce((sum, q) => sum + q.minutes, 0);

  return (
    <article className="unit project">
      <nav className="crumbs" aria-label="Breadcrumb">
        <Link to="/">Home</Link> › <Link to="/projects">My projects</Link>
      </nav>
      <h2>{project.name}</h2>
      <p className="meta">
        {project.questions.length === 1 ? "1 question" : `${project.questions.length} questions`} · about {minutes} min
      </p>
      {project.summary && <Markdown>{project.summary}</Markdown>}
      {project.questions.length === 0 && <p className="hint">This project has no questions yet.</p>}
      {project.questions.map((q, i) => (
        <Question key={q.id} n={i + 1} question={q} onChange={(update) => change(q.id, update)} />
      ))}
    </article>
  );
}

function Question({ n, question: q, onChange }: {
  n: number;
  question: ProjectQuestion;
  onChange: (update: Partial<ProjectQuestion>) => void;
}) {
  const unfolded = canUnfold(q);
  const spoilers = q.probes.trim() !== "" || q.strongAnswer.trim() !== "";
  return (
    <section className="project-question" aria-labelledby={`q-${q.id}`}>
      <p className="meta">
        {n}. {RUNG_LABEL[q.rung] ?? q.rung} · {q.minutes} min
      </p>
      <h3 id={`q-${q.id}`}>{q.prompt}</h3>
      <AnswerBox
        questionId={q.id}
        initial={q.answer}
        answeredAt={q.answeredAt}
        onSaved={(answer, answeredAt) => onChange({ answer, answeredAt })}
      />
      {q.units.length > 0 && (
        <p className="hint">
          The idea underneath:{" "}
          {q.units.map((u, i) => (
            <span key={u.id}>
              {i > 0 && ", "}
              <Link to={`/units/${u.id}`}>{u.title}</Link>
            </span>
          ))}
        </p>
      )}
      {spoilers && !unfolded && (
        <p className="hint">
          The follow-ups and what a strong answer covers open once you have written an answer or rated this question.
        </p>
      )}
      {spoilers && unfolded && (
        <>
          {q.probes.trim() !== "" && (
            <details className="folded">
              <summary>Show the follow-ups</summary>
              <Markdown>{q.probes}</Markdown>
            </details>
          )}
          {q.strongAnswer.trim() !== "" && (
            <details className="folded">
              <summary>Show what a strong answer covers</summary>
              <Markdown>{q.strongAnswer}</Markdown>
            </details>
          )}
        </>
      )}
      <RatingPanel
        level={4}
        progress={q.progress}
        onProgress={(progress) => onChange({ progress })}
        rate={(rating) => rateQuestion(q.id, rating)}
        undo={() => undoQuestionRating(q.id)}
        words={QUESTION_WORDS}
        howToReview={HOW_TO_REHEARSE}
      />
    </section>
  );
}
