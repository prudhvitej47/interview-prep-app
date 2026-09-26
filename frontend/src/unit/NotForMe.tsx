import { useEffect, useState } from "react";
import { fetchNotForMe, setNotForMe, type NotForMe as Marks } from "../api";

/**
 * "Not for me": keeps one topic or unit out of this learner's own plans. Weights move whole areas;
 * this is for one piece inside an area the learner otherwise wants. It never changes the shared
 * curriculum or the other learner's plans, and it is undone with one click.
 */
export function NotForMe({ scope, id, topicId, topicName }: {
  scope: "topic" | "unit";
  id: string;
  /** On a unit page: its topic, so a unit whose whole topic is out can say so. */
  topicId?: string;
  topicName?: string;
}) {
  const [marks, setMarks] = useState<Marks | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    // If this cannot load, the control simply does not show: the unit itself matters more.
    fetchNotForMe().then((m) => live && setMarks(m)).catch(() => undefined);
    return () => {
      live = false;
    };
  }, []);

  if (error) return <p className="hint" role="alert">{error}</p>;
  if (!marks) return null;

  const topicOut = scope === "unit" && topicId !== undefined && marks.topics.includes(topicId);
  const out = (scope === "topic" ? marks.topics : marks.units).includes(id);
  const noun = scope === "topic" ? "this topic" : "this unit";

  const change = async (excluded: boolean) => {
    setError(null);
    try {
      setMarks(await setNotForMe(scope, id, excluded));
    } catch {
      setError("Could not save that. Try again.");
    }
  };

  return (
    <section className="not-for-me" aria-label="Not for me">
      {topicOut ? (
        <p className="hint">
          Its topic, {topicName ?? topicId}, is out of your plans, so this unit is too. Bring the topic back from its page.
        </p>
      ) : out ? (
        <p className="hint">
          You have kept {noun} out of your plans.{" "}
          <button className="link" onClick={() => change(false)}>Put it back</button>
        </p>
      ) : (
        <p className="hint">
          Not for you?{" "}
          <button className="link" onClick={() => change(true)}>Keep {noun} out of my plans</button>
          . Only your plans change, from the next one drawn; you can still open it here.
        </p>
      )}
    </section>
  );
}
