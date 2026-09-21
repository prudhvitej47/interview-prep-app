import { useEffect, useState } from "react";
import { fetchDomains, saveDomainRatings, type Domain, type Me } from "./api";

// Anchors, so that a 3 means the same thing to both learners and to the planner.
const SCALE = [
  "New to me",
  "Heard of it",
  "Used it a little",
  "Comfortable",
  "Strong",
  "Could teach it",
];

type Props = { me: Me; onDone: (me: Me) => void };

export function Onboarding({ me, onDone }: Props) {
  const [domains, setDomains] = useState<Domain[] | null>(null);
  const [ratings, setRatings] = useState<Record<string, number>>(me.domainRatings);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    fetchDomains().then(setDomains).catch((e: Error) => setError(e.message));
  }, []);

  if (error && !domains) return <p role="alert">{error}</p>;
  if (!domains) return <p>Loading…</p>;
  if (domains.length === 0) {
    return <p>No curriculum has been loaded yet, so there is nothing to rate. Try again shortly.</p>;
  }

  const remaining = domains.filter((d) => ratings[d.id] === undefined).length;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    try {
      onDone(await saveDomainRatings(ratings));
    } catch (e) {
      setError((e as Error).message);
      setSaving(false);
    }
  }

  return (
    <form onSubmit={submit} className="onboarding">
      <h2>Where are you starting from?</h2>
      <p>
        Rate yourself on each area. It only sets the starting point for your first plans — real
        practice replaces these numbers within a few weeks, and you can change them any time.
      </p>

      {domains.map((d) => (
        <fieldset key={d.id}>
          <legend>{d.name}</legend>
          {d.examples.length > 0 && <p className="examples">{d.examples.join(" · ")}</p>}
          <div className="scale">
            {SCALE.map((label, value) => (
              <label key={value}>
                <input
                  type="radio"
                  name={d.id}
                  value={value}
                  checked={ratings[d.id] === value}
                  onChange={() => setRatings({ ...ratings, [d.id]: value })}
                />
                <span>
                  {value} <small>{label}</small>
                </span>
              </label>
            ))}
          </div>
        </fieldset>
      ))}

      {error && <p role="alert">{error}</p>}
      <button type="submit" disabled={remaining > 0 || saving}>
        {saving ? "Saving…" : remaining > 0 ? `${remaining} left to rate` : "Save and continue"}
      </button>
    </form>
  );
}
