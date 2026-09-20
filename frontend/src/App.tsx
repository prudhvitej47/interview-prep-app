import { useEffect, useState } from "react";
import { fetchTopics, type TopicSummary } from "./api";

type Load =
  | { state: "loading" }
  | { state: "ready"; topics: TopicSummary[] }
  | { state: "failed"; message: string };

export function App() {
  const [load, setLoad] = useState<Load>({ state: "loading" });

  useEffect(() => {
    let live = true;
    fetchTopics()
      .then((topics) => live && setLoad({ state: "ready", topics }))
      .catch((error: Error) => live && setLoad({ state: "failed", message: error.message }));
    return () => {
      live = false;
    };
  }, []);

  return (
    <main>
      <h1>Interview Prep</h1>
      {load.state === "loading" && <p>Loading the curriculum…</p>}
      {load.state === "failed" && <p role="alert">{load.message}</p>}
      {load.state === "ready" && (
        <p>
          {load.topics.length === 0
            ? "Connected. No curriculum loaded yet — that arrives with the content loader."
            : `${load.topics.length} topics loaded.`}
        </p>
      )}
    </main>
  );
}
