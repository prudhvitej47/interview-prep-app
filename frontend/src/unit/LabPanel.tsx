import { useEffect, useId, useRef, useState } from "react";
import { checkLabToken, fetchLabStatus, labStarterUrl, type Lab } from "../api";
import { Markdown } from "./Markdown";

const NEEDS: Record<string, string> = { jdk: "JDK 25", jbang: "JBang", docker: "Docker" };

/** What a learner pasted, as the server reads it: the whole "TOKEN: …" line is fine. */
function normalise(raw: string): string {
  const t = raw.trim().toLowerCase();
  return t.startsWith("token:") ? t.slice("token:".length).trim() : t;
}

const shown = (iso: string) => new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });

/**
 * A laptop lab: download the starter, work on your own machine, paste the token LabCheck prints when
 * every check passes. The server checks the token. "Lab done" is recorded on its own and is not a
 * rating: the unit is still marked done only by rating it, so stars and reviews are unchanged.
 */
export function LabPanel({ unitId, lab }: { unitId: string; lab: Lab }) {
  const inputId = useId();
  const [doneAt, setDoneAt] = useState<string | null>(null);
  const [token, setToken] = useState("");
  const [message, setMessage] = useState<{ right: boolean; text: string } | null>(null);
  const status = useRef<HTMLParagraphElement>(null);
  const cleaned = normalise(token);
  const ready = /^[0-9a-f]{8}$/.test(cleaned);

  useEffect(() => {
    fetchLabStatus(unitId).then((s) => setDoneAt(s.doneAt)).catch(() => setDoneAt(null));
  }, [unitId]);

  async function check() {
    try {
      const result = await checkLabToken(unitId, cleaned);
      if (result.doneAt) setDoneAt(result.doneAt);
      setMessage(result.right
        ? { right: true, text: "Right: the lab is done. Rate the unit as usual when you have finished it." }
        : { right: false, text: "That token does not match. Run LabCheck again and copy its TOKEN line exactly." });
    } catch (e) {
      setMessage({ right: false, text: (e as Error).message });
    }
    status.current?.focus();
  }

  return (
    <section className="lab" aria-label="Laptop lab">
      <h3>Lab: {lab.title}</h3>
      <p className="meta">About {lab.minutes} min on your laptop · needs {lab.needs.map((n) => NEEDS[n] ?? n).join(", ")}</p>
      {doneAt && <p className="lab-done">✓ Lab done {shown(doneAt)}</p>}
      <p><a href={labStarterUrl(unitId)} download>Download the starter (.zip)</a></p>
      <details className="folded">
        <summary>Show the lab steps</summary>
        <Markdown>{lab.readme}</Markdown>
      </details>
      <form className="lab-token" onSubmit={(e) => { e.preventDefault(); if (ready) void check(); }}>
        <label htmlFor={inputId}>Token from LabCheck</label>
        <input id={inputId} value={token} onChange={(e) => setToken(e.target.value)} spellCheck={false}
          autoComplete="off" placeholder="TOKEN: 1a2b3c4d" />
        <button type="submit" className="secondary" disabled={!ready}>Check</button>
      </form>
      <p role="status" tabIndex={-1} ref={status} className={message ? `verdict ${message.right ? "right" : "wrong"}` : "practical-status"}>
        {message?.text ?? ""}
      </p>
    </section>
  );
}
