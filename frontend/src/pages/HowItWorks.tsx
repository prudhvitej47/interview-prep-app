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
          <li>
            It plans 90% of your hours, leaving room for a busy day. Reviews take up to a fifth of that, and
            questions about <Link to="/projects">your own projects</Link> up to a tenth, before the areas share the
            rest.
          </li>
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
          <li>
            Project questions: those you did not rate last week come back first (sharing that 30%), then those due
            for another rehearsal, then the next rungs of one project's ladder, finishing a project you have started
            before beginning another. Never two on one day, and at least one a week when a tenth of it has room for
            one.
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
            problem rated Easy). A question about your own project earns 1 the first time you rate it anything but
            Again. A day with three or more reviews earns 1. A week that meets its goal earns 5, and 2 more if the
            whole plan was done; a planned project question counts once you rate it that week.
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

      <section aria-labelledby="h-projects">
        <h3 id="h-projects">8. Your own projects</h3>
        <p>
          <Link to="/projects">My projects</Link> holds the questions an interviewer asks about work you have done
          (walk me through it, why that way, what breaks at scale, what happens when a part dies, what you would
          change, and the story behind it), with your answer to each. They are private to you, and they never go
          into the curriculum's repository.
        </p>
        <ul>
          <li>
            They arrive as a question file you <strong>import</strong>: a JSON file with <code>"version": 1</code> and
            a list of projects, each with a <code>key</code>, <code>name</code>, <code>summary</code> and
            its <code>questions</code> (<code>key</code>, <code>rung</code>, <code>prompt</code>,{" "}
            <code>probes</code>, <code>strong_answer</code>, <code>units</code>, <code>minutes</code>). Up to 30
            projects of 30 questions, and 1 MB.
          </li>
          <li>
            Importing an edited file again is safe: questions are matched by their key, so your answers and ratings
            stay. One missing from the new file is hidden, not deleted.
          </li>
          <li>
            Your answer saves as you type. The follow-ups and what a strong answer covers stay closed until you have
            written an answer or rated the question, so you rehearse before you read.
          </li>
          <li>
            Rating a question schedules it like a unit's review, and due ones show on the home page under their own
            heading. Each week's plan includes some of them, as section 1 describes. <strong>Export</strong> downloads everything, answers and ratings included.
          </li>
        </ul>
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
