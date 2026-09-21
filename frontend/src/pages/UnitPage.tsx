import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { fetchUnit, NotFoundError, type UnitDetail } from "../api";
import { Markdown } from "../unit/Markdown";
import { NoteEditor } from "../unit/NoteEditor";
import { splitSections, TYPE_LABEL } from "../unit/sections";

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

  return (
    <article className={`unit unit-${unit.type}`}>
      <nav className="crumbs" aria-label="Breadcrumb">
        <Link to="/">Home</Link> › {unit.domainName} › <Link to={`/topics/${unit.topicId}`}>{unit.topicName}</Link>
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

      {splitSections(unit.markdown, unit.type).map((section, i) =>
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
      )}

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

      <NoteEditor unitId={unit.id} />
    </article>
  );
}
