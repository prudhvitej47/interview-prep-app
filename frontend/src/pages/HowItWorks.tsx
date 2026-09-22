import { useState } from "react";
import { Link } from "react-router";
import { setStartGuideClosed, type Me } from "../api";

/**
 * How the app works, for a first-time learner and for anyone who wonders why the plan picked what
 * it did. The numbers here are the planner's and the rewards' own constants (WeekPlanner,
 * ReviewSchedule, Rewards, BreakController): change one there, change it here.
 */
export function HowItWorks({ me, onMe }: { me: Me; onMe: (me: Me) => void }) {
  const [error, setError] = useState<string | null>(null);

  async function reopen() {
    setError(null);
    try {
      onMe(await setStartGuideClosed(false));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <article className="how-it-works">
      <nav className="crumbs" aria-label="Breadcrumb">
        <Link to="/">Home</Link> › How this works
      </nav>
      <h2>How this works</h2>
      <p>
        One curriculum, written for senior backend interviews, and a plan built for you each week from it. You
        do a little on each study day; the app keeps track of what is due, what is next and how the week is going.
      </p>

      <section aria-labelledby="h-week">
        <h3 id="h-week">1. Your week</h3>
        <p>
          On <Link to="/week">This week's plan</Link> you say how many hours you have, on which days, and
          optionally how much each area matters to you. Each week's plan is built the first time you open
          that week (weeks start on Monday):
        </p>
        <ul>
          <li>It plans 90% of your hours, leaving room for a busy day. Reviews take up to a fifth of that.</li>
          <li>
            Each area gets a share of the time from its <strong>weight</strong>, multiplied by how weak you are in
            it: up to 1.5× for a weak area, down to 0.7× for a strong one, never below half or above double its
            weight.
          </li>
          <li>
            Within an area, your weakest topics come first, and units that show up in real interview reports
            rank higher.
          </li>
          <li>
            Every week mixes DSA with Java or Spring, databases or distributed systems, low-level design, system
            design and a scenario or story. At most two long units (75 minutes or more) a week.
          </li>
          <li>
            Units you did not finish last week come back first, using up to 30% of the time, and each comes back
            at most twice. After that it waits its turn like any other unit.
          </li>
          <li>The week's <strong>goal</strong> is 80% of the planned minutes.</li>
        </ul>
        <p className="hint">
          Changing your hours, days or weights redraws the plan; everything you have done stays done.
        </p>
      </section>

      <section aria-labelledby="h-ratings">
        <h3 id="h-ratings">2. Ratings and weights are different things</h3>
        <table>
          <tbody>
            <tr>
              <th>Ratings</th>
              <td>
                How strong you are, 0 to 5, per area (asked once at the start) or per topic (when the plan asks
                "how are you with these?"). They steer time toward what you are weak at. Real practice replaces
                them within a few weeks. <Link to="/ratings">Change my ratings</Link>.
              </td>
            </tr>
            <tr>
              <th>Weights</th>
              <td>
                How much an area matters for the roles you are aiming at. Relative: 20 gets twice the time of 10;
                0 leaves an area out. Blank keeps the curriculum's default. Set them on the week page under
                "Weights by area".
              </td>
            </tr>
          </tbody>
        </table>
      </section>

      <section aria-labelledby="h-unit">
        <h3 id="h-unit">3. Doing a unit</h3>
        <ul>
          <li>
            Each kind of unit has its own shape: a concept explains a mechanism, a coding or SQL problem is
            solved, a design case is drawn, a scenario is diagnosed.
          </li>
          <li>
            Hints, solutions and diagnoses are folded until you open them, so you can try first.
          </li>
          <li>
            SQL problems run in your browser on a small sample database: <strong>Try it</strong>, and
            <strong> Reset data</strong> if you change it. Coding problems list test cases to check your own
            solution against.
          </li>
          <li>Your notes on a unit are private to you.</li>
        </ul>
      </section>

      <section aria-labelledby="h-reviews">
        <h3 id="h-reviews">4. Marking it done, and reviews</h3>
        <p>
          At the bottom of a unit, say how it went. That marks it done and decides when it comes back for a short
          review. Reviews space out roughly 1, 3, 7, 16 and 35 days, then keep stretching:
        </p>
        <table>
          <tbody>
            <tr><th>Again</th><td>You could not do it. Starts over: back tomorrow.</td></tr>
            <tr><th>Hard</th><td>Managed with effort or a peek. Same gap again.</td></tr>
            <tr><th>Good</th><td>Done with some thought. One step further out.</td></tr>
            <tr><th>Easy</th><td>Straightforward. Two steps further out.</td></tr>
          </tbody>
        </table>
        <p>Reviews that are due show at the top of the home page.</p>
      </section>

      <section aria-labelledby="h-rewards">
        <h3 id="h-rewards">5. Stars, streak, freezes and breaks</h3>
        <ul>
          <li>
            <strong>Stars</strong>, never taken away: a unit earns them the first time it is done with anything but
            Again (3 for a design case, 2 for a project deep dive, 1 otherwise, and 1 more for a coding or SQL
            problem rated Easy). A day with three or more reviews earns 1. A week that meets its goal earns 5, and
            2 more if the whole plan was done.
          </li>
          <li><strong>Streak</strong>: weeks in a row that met their goal.</li>
          <li>
            <strong>Freezes</strong>: a missed week uses one instead of breaking the streak. You start with 1, earn
            1 more for every 4 goal weeks in a row, and can hold 2.
          </li>
          <li>
            <strong>Planned breaks</strong>: up to 2 a quarter, booked ahead on the week page. A break week has no
            plan and neither counts toward nor breaks the streak.
          </li>
        </ul>
      </section>

      <section aria-labelledby="h-changed">
        <h3 id="h-changed">6. When the curriculum changes</h3>
        <p>
          New units arrive as releases. The <strong>What changed</strong> card on the home page lists them, with a
          suggestion for each; choose Now, Next week or Later. Your progress is kept when a unit is updated.
        </p>
      </section>

      <section aria-labelledby="h-evidence">
        <h3 id="h-evidence">7. Interview evidence</h3>
        <p>
          <Link to="/evidence">Interview evidence</Link> holds the interview reports the curriculum is built on.
          After one of your own interviews, <strong>log a debrief</strong>: it stays private until you choose
          <strong> Send to the curriculum</strong>. Something useful you read goes in through <strong>Add an
          article</strong>. Either way it becomes a proposal that is reviewed before any unit changes.
        </p>
      </section>

      <section aria-labelledby="h-guide">
        <h3 id="h-guide">The start guide</h3>
        {me.startGuideClosed ? (
          <p>
            The "Start here" card on the home page is closed.{" "}
            <button className="link" onClick={reopen}>Show it again</button>
          </p>
        ) : (
          <p>The "Start here" card is showing on the <Link to="/">home page</Link>.</p>
        )}
        {error && <p role="alert">{error}</p>}
      </section>
    </article>
  );
}
