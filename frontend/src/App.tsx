import { useEffect, useState } from "react";
import { fetchMe, fetchTopics, NotAllowedError, type Me } from "./api";
import { Onboarding } from "./Onboarding";

type State =
  | { kind: "loading" }
  | { kind: "not-allowed" }
  | { kind: "failed"; message: string }
  | { kind: "onboarding"; me: Me }
  | { kind: "ready"; me: Me; topics: number };

export function App() {
  const [state, setState] = useState<State>({ kind: "loading" });

  async function showHome(me: Me) {
    const topics = await fetchTopics();
    setState({ kind: "ready", me, topics: topics.length });
  }

  useEffect(() => {
    let live = true;
    fetchMe()
      .then((me) => {
        if (!live) return;
        if (me.onboarded) return showHome(me);
        setState({ kind: "onboarding", me });
      })
      .catch((error: Error) => {
        if (!live) return;
        setState(
          error instanceof NotAllowedError
            ? { kind: "not-allowed" }
            : { kind: "failed", message: error.message },
        );
      });
    return () => {
      live = false;
    };
  }, []);

  return (
    <main>
      <h1>Interview Prep</h1>
      {state.kind === "loading" && <p>Loading…</p>}
      {state.kind === "failed" && <p role="alert">{state.message}</p>}
      {state.kind === "not-allowed" && (
        <p role="alert">
          You reached the app, but you are not on its list of learners. It recognises you by the
          account you are signed in to Tailscale with.
        </p>
      )}
      {state.kind === "onboarding" && (
        <Onboarding
          me={state.me}
          onDone={(me) => showHome(me).catch((e: Error) => setState({ kind: "failed", message: e.message }))}
        />
      )}
      {state.kind === "ready" && (
        <>
          <p>Welcome, {state.me.displayName}.</p>
          <p>
            {state.topics === 0
              ? "No curriculum loaded yet — that arrives with the content loader."
              : `${state.topics} topics loaded.`}
          </p>
        </>
      )}
    </main>
  );
}
