import { useEffect, useState } from "react";
import { Link } from "react-router";
import { fetchEvidenceOptions, sendArticle, type Option } from "../api";

/**
 * An interview experience or article someone else wrote, sent as raw material to an inbox/ branch
 * of the curriculum. An ingest run turns it into evidence (paraphrased questions mapped to topics)
 * and any new units, and opens a proposal for review.
 */
export function ArticlePage() {
  const [article, setArticle] = useState({ title: "", url: "", company: "", text: "" });
  const [companies, setCompanies] = useState<Option[]>([]);
  const [problems, setProblems] = useState<string[]>([]);
  const [sent, setSent] = useState<{ branch: string; url: string } | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [sending, setSending] = useState(false);

  useEffect(() => {
    fetchEvidenceOptions().then((o) => setCompanies(o.companies)).catch(() => undefined);
  }, []);

  async function send() {
    setSending(true);
    setProblems([]);
    setStatus(null);
    try {
      const result = await sendArticle(article);
      if ("problems" in result) setProblems(result.problems);
      else if ("notSetUp" in result) {
        setStatus("Sending is not set up on this server yet. Save the text into the content repository's inbox/ instead.");
      } else setSent(result);
    } catch (e) {
      setStatus((e as Error).message);
    } finally {
      setSending(false);
    }
  }

  const set = (patch: Partial<typeof article>) => setArticle({ ...article, ...patch });
  return (
    <article className="evidence draft">
      <nav className="crumbs" aria-label="Breadcrumb">
        <Link to="/">Home</Link> › <Link to="/evidence">Interview evidence</Link> › Add an article
      </nav>
      <h2>Add an article or interview experience</h2>
      <p className="hint">
        Something you read: a Medium post, a LeetCode or Glassdoor experience, a blog. Paste the text as it is; the ingest
        run writes the questions in its own words, maps them to topics and drafts any units, then opens a proposal for you
        to review. The pasted text itself never goes into the curriculum.
      </p>
      {sent ? (
        <div className="banner" role="note">
          <p>
            Sent to the curriculum inbox as <code>{sent.branch}</code>.{" "}
            <a href={sent.url} target="_blank" rel="noreferrer noopener">See it on GitHub</a>.
          </p>
          <p>
            Next: an ingest run turns it into a proposal. Ask Claude to "process the inbox", or it happens in the weekly
            run once that is set up.
          </p>
          <p>
            <button className="link" onClick={() => { setSent(null); setArticle({ title: "", url: "", company: "", text: "" }); }}>
              Add another
            </button>
          </p>
        </div>
      ) : (
        <fieldset>
          <label>Title <input value={article.title} onChange={(e) => set({ title: e.target.value })} placeholder="My Stripe senior loop" /></label>
          <label>Link <input value={article.url} onChange={(e) => set({ url: e.target.value })} placeholder="https://" /></label>
          <label>Company (if it is about one){" "}
            <select value={article.company} onChange={(e) => set({ company: e.target.value })}>
              <option value="">Not one company</option>
              {companies.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </label>
          <label>The text
            <textarea rows={14} value={article.text} onChange={(e) => set({ text: e.target.value })}
              placeholder="Paste it here. For a public page, the link alone is enough." />
          </label>
          <div className="note-actions">
            <button onClick={send} disabled={sending}>{sending ? "Sending…" : "Send to the curriculum"}</button>
            <span role="status">{status}</span>
          </div>
        </fieldset>
      )}
      {problems.length > 0 && (
        <div role="alert" className="verdict wrong">
          <p>Before it can be sent:</p>
          <ul>{problems.map((p) => <li key={p}>{p}</li>)}</ul>
        </div>
      )}
    </article>
  );
}
