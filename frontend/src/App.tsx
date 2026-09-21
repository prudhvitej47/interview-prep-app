import { useEffect, useState } from "react";
import { Link, Route, Routes, useNavigate } from "react-router";
import { fetchMe, NotAllowedError, type Me } from "./api";
import { Onboarding } from "./Onboarding";
import { Home } from "./pages/Home";
import { TopicPage } from "./pages/TopicPage";
import { UnitPage } from "./pages/UnitPage";

type State =
  | { kind: "loading" }
  | { kind: "not-allowed" }
  | { kind: "failed"; message: string }
  | { kind: "ready"; me: Me };

export function App() {
  const [state, setState] = useState<State>({ kind: "loading" });
  const navigate = useNavigate();

  useEffect(() => {
    let live = true;
    fetchMe()
      .then((me) => live && setState({ kind: "ready", me }))
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

  const saved = (me: Me) => {
    setState({ kind: "ready", me });
    navigate("/");
  };

  return (
    <main>
      <h1>
        <Link to="/">Interview Prep</Link>
      </h1>
      {state.kind === "loading" && <p>Loading…</p>}
      {state.kind === "failed" && <p role="alert">{state.message}</p>}
      {state.kind === "not-allowed" && (
        <p role="alert">
          You reached the app, but you are not on its list of learners. It recognises you by the
          account you are signed in to Tailscale with.
        </p>
      )}
      {/* Until the starting point is set there is nothing sensible to show, whatever the address. */}
      {state.kind === "ready" && !state.me.onboarded && <Onboarding me={state.me} onDone={saved} />}
      {state.kind === "ready" && state.me.onboarded && (
        <Routes>
          <Route path="/" element={<Home me={state.me} />} />
          <Route
            path="/ratings"
            element={<Onboarding me={state.me} onDone={saved} title="Change your ratings" />}
          />
          <Route path="/topics/:topicId" element={<TopicPage />} />
          <Route path="/units/:unitId" element={<UnitPage />} />
          <Route path="*" element={<p role="alert">There is nothing at this address.</p>} />
        </Routes>
      )}
    </main>
  );
}
