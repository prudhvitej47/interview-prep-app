import { useEffect, useState } from "react";
import { Link, NavLink, Route, Routes, useLocation, useNavigate } from "react-router";
import { fetchMe, NotAllowedError, type Me } from "./api";
import { Onboarding } from "./Onboarding";
import { Home } from "./pages/Home";
import { TopicPage } from "./pages/TopicPage";
import { WeekPage } from "./pages/WeekPage";
import { EvidencePage } from "./pages/EvidencePage";
import { EvidenceDetailPage } from "./pages/EvidenceDetailPage";
import { DraftPage } from "./pages/DraftPage";
import { ArticlePage } from "./pages/ArticlePage";
import { UnitPage } from "./pages/UnitPage";
import { HowItWorks } from "./pages/HowItWorks";
import { useHeadingFocus, useScrollRestoration } from "./navigation";

type State =
  | { kind: "loading" }
  | { kind: "not-allowed" }
  | { kind: "failed"; message: string }
  | { kind: "ready"; me: Me };

export function App() {
  const [state, setState] = useState<State>({ kind: "loading" });
  const navigate = useNavigate();
  const { pathname } = useLocation();
  // A unit or a topic is the curriculum, and the curriculum lives on the home page - which is also
  // where the breadcrumb goes back to. Without this no pill was marked at all on those pages, and
  // a row of four evenly muted links read as though it had shifted.
  const inCurriculum = /^\/(units|topics)(\/|$)/.test(pathname);
  useScrollRestoration();
  useHeadingFocus();

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
      {/* On every page, so no trip goes through the home page to reach another section. */}
      {state.kind === "ready" && state.me.onboarded && (
        <nav className="sections" aria-label="Sections">
          <NavLink to="/" end className={({ isActive }) => (isActive || inCurriculum ? "active" : "")}>
            Home
          </NavLink>
          <NavLink to="/week">This week</NavLink>
          <NavLink to="/evidence">Interview evidence</NavLink>
          <NavLink to="/how-it-works">How this works</NavLink>
        </nav>
      )}
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
          <Route path="/" element={<Home me={state.me} onMe={(me) => setState({ kind: "ready", me })} />} />
          <Route path="/how-it-works"
            element={<HowItWorks me={state.me} onMe={(me) => setState({ kind: "ready", me })} />} />
          <Route
            path="/ratings"
            element={<Onboarding me={state.me} onDone={saved} title="Change your ratings" />}
          />
          <Route path="/week" element={<WeekPage />} />
          <Route path="/evidence" element={<EvidencePage />} />
          <Route path="/evidence/drafts/:draftId" element={<DraftPage />} />
          <Route path="/evidence/articles/new" element={<ArticlePage />} />
          <Route path="/evidence/:evidenceId" element={<EvidenceDetailPage />} />
          <Route path="/topics/:topicId" element={<TopicPage />} />
          <Route path="/units/:unitId" element={<UnitPage />} />
          <Route path="*" element={<p role="alert">There is nothing at this address.</p>} />
        </Routes>
      )}
    </main>
  );
}
