import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { fetchUnit, NotFoundError, type UnitDetail } from "../api";
import { Markdown } from "../unit/Markdown";
import { NoteEditor } from "../unit/NoteEditor";
import { NotForMe } from "../unit/NotForMe";
import { PlanNav } from "./PlanNav";
import { ProgressPanel } from "../unit/ProgressPanel";
import { splitSections, TYPE_LABEL } from "../unit/sections";
import { CodingPractice } from "../practice/CodingPractice";
import { SqlPractice } from "../practice/SqlPractice";

export function UnitPage() {
  const { unitId = "" } = useParams();
  const [unit, setUnit] = useState<UnitDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setUnit(null);
    fetchUnit(unitId)
      .then(setUnit)
      .catch((e: Error) => setError(e instanceof NotFoundError ? "There is no such unit." : e.message));
  }, [unitId]);

  if (error) return <p role="alert">{error}</p>;
  if (!unit) return <p>Loading…</p>;

  const sections = splitSections(unit.markdown, unit.type);
  const practice = unit.sqlFixture ? (
    <SqlPractice key="practice" fixture={unit.sqlFixture} />
  ) : unit.testCases.length > 0 ? (
    <CodingPractice key="practice" cases={unit.testCases} hidden={unit.hiddenTestCases} />
  ) : null;
  // Practice goes just before the first spoiler (hints, solution), so the attempt comes first.
  const firstFolded = sections.findIndex((s) => s.folded);
  const practiceAt = firstFolded === -1 ? sections.length : firstFolded;

  return (
    <article className={`unit unit-${unit.type}`}>
      <nav className="crumbs" aria-label="Breadcrumb">
        <Link to="/">Home</Link> › <Link to={`/#domain-${unit.domainId}`}>{unit.domainName}</Link> › <Link to={`/topics/${unit.topicId}`}>{unit.topicName}</Link>
      </nav>
      <h2>{unit.title}</h2>
      <p className="meta">
        {TYPE_LABEL[unit.type] ?? unit.type} · difficulty {unit.difficulty}/5 · {unit.estMinutes} min
        {unit.rounds.length > 0 && <> · asked in {unit.rounds.join(", ")} rounds</>}
      </p>
      {unit.state === "retired" && (
        <p className="banner" role="note">
          This unit has been retired from the curriculum. It is kept because your history may refer to it.
        </p>
      )}

      {sections.map((section, i) => [
        i === practiceAt && practice,
        section.heading === null ? (
          <Markdown key={i}>{section.body}</Markdown>
        ) : section.folded ? (
          <details key={i} className="folded">
            <summary>Show {section.heading.toLowerCase()}</summary>
            <Markdown>{section.body}</Markdown>
          </details>
        ) : (
          <section key={i}>
            <h3>{section.heading}</h3>
            <Markdown>{section.body}</Markdown>
          </section>
        ),
      ])}
      {practiceAt === sections.length && practice}

      {unit.prerequisites.length > 0 && (
        <section>
          <h3>Before this</h3>
          <ul>
            {unit.prerequisites.map((p) => (
              <li key={p.id}>
                <Link to={`/units/${p.id}`}>{p.title}</Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      {unit.sources.length > 0 && (
        <section className="sources">
          <h3>Sources</h3>
          <ul>
            {unit.sources.map((s, i) => (
              <li key={i}>
                {s.url ? (
                  <a href={s.url} target="_blank" rel="noreferrer noopener">{s.title}</a>
                ) : (
                  s.title
                )}
                {s.locator && <>, {s.locator}</>}
              </li>
            ))}
          </ul>
        </section>
      )}

      {unit.state !== "retired" && <ProgressPanel unitId={unit.id} type={unit.type} />}
      <PlanNav unitId={unit.id} />
      {unit.state !== "retired" && (
        <NotForMe scope="unit" id={unit.id} topicId={unit.topicId} topicName={unit.topicName} />
      )}
      <NoteEditor unitId={unit.id} />
    </article>
  );
}
