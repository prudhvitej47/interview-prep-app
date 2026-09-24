# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Two senior backend engineers preparing for interviews at large technology companies, studying
alongside full-time work. They share one curriculum but have separate plans, ratings, progress and
stories.

Where they use it, confirmed:

- **Laptop, sitting down to study** — the main session. Reading a unit, solving a coding or SQL
  problem in the browser, marking work done. This is where most minutes are spent.
- **Phone, for quick checks** — what is due, what changed, what is next. Legibility matters more
  than completeness; nobody is solving a problem on a phone.
- **Tablet, for reading** — units read in portrait, at a width between the two above.

No other audience exists. There are no anonymous visitors, no trial users and no administrators.

## Product Purpose

Turn interview preparation from a pile of bookmarks into a plan that adapts. Each week the app
builds a study plan from one curriculum, weighted by what each learner is weak at and what real
interviews actually ask, schedules reviews so finished work is not forgotten, and records interview
reports as evidence that changes what gets taught next.

Working well over the next quarter means, in the learners' own order:

1. **Both learners complete two planned weeks.** The plan is followed, not merely generated.
2. **Interview outcomes improve** — fewer rounds lost to the same gap twice; the weak areas the app
   names actually close.
3. **The content pipeline runs itself** — a report becomes a proposal, gets reviewed, merges, and
   reaches a plan without anyone hand-carrying each step.

## Positioning

Curriculum as code, with evidence attached. Every unit is a reviewed file in a Git repository, every
company tag traces to a recorded interview report, and a single report can only add material — it
can never move the weights that decide how time is spent. A subscription course cannot show its
sources; a folder of bookmarks cannot build a week.

The planner is the other half: it spends a declared budget of hours across required areas, favours
weak topics, carries unfinished work forward, and explains per item why it is there.

## Operating Context

- Reachable only inside a private network (Tailscale), at one address, by two accounts. Identity
  comes from the network; there is no sign-up, no password and no public entry point.
- One small virtual machine runs the app and its database. The curriculum arrives as a published
  bundle and is reloaded when it changes; progress keys off stable unit ids, so content can be
  edited without losing history.
- A study session is short and repeated: open the week, do one or two units, mark them, leave.
  Reviews arrive on their own schedule and are the reason to return on a day with no new material.
- After a real interview, a debrief is written in the app and can be sent to the curriculum
  repository as a proposal for review.

## Capabilities and Constraints

Confirmed capabilities: weekly plan generation with per-item reasons; spaced review scheduling from
self-rated attempts; stars, streaks, freezes and planned breaks; coverage and weak-area views; the
"what changed" card with per-unit placement choices; SQL practice that runs in the browser; coding
problems with visible test cases; private per-unit notes; interview evidence browsing, debriefs and
article capture; sending either to the curriculum repository as a branch.

Constraints that future work must preserve:

- **Nothing personal in this repository.** It is public. No names, employers, career history or
  learner-specific content in code, documentation or this file. Personal answers — stories, project
  notes, ratings — exist only in the application's own database.
- **Private by construction.** No sign-up, no analytics, no third-party fonts, scripts or trackers,
  and no data leaving the machine except a deliberate push to the curriculum repository.
- **Small machine, two users.** Throughput is irrelevant; memory is not. A page that needs a large
  runtime download has to justify it.
- **Stable unit ids.** Progress, reviews and evidence all reference them; they outlive edits.

## Brand Commitments

- **Plain and quiet.** This is a study tool, not a product launch. Restraint in colour and motion;
  nothing on the page competes with the unit being read.
- **Room to be bolder.** The current appearance is a first pass, not a commitment. A stronger visual
  identity is welcome where it stays legible and fast — and the two rules above still bind it.
- Voice: direct and unhedged, the same register the curriculum is written in. No marketing language,
  no exclamation, no encouragement the learner has not earned.

## Evidence on Hand

- A working application in daily use by both learners, with real progress in it.
- A curriculum of 111 units across twelve domains, each carrying sources; 23 recorded interview
  reports behind the company tags.
- No testimonials, customers, pricing, benchmarks or press exist. Future work must not invent any:
  there is nothing to quote and nobody to quote it from.

## Product Principles

1. **The evidence decides.** What gets taught, and what a company tag claims, traces to a recorded
   report. One report adds material; only a pattern moves weights.
2. **The plan must be defensible.** Every item shows why it is there. A learner who disagrees with
   the week should be able to see the reasoning and change the inputs.
3. **Finished work is not lost.** Reviews, carry-over and stable ids exist so that effort compounds
   rather than evaporating.
4. **Nothing personal leaves the machine.** The public half is the method; the private half is the
   person.
5. **Quiet beats impressive.** Attention belongs to the material. Interface personality is earned in
   precision, not in decoration.

## Accessibility & Inclusion

No formal standard has been set. Known requirements from use: readable at phone width without
horizontal scrolling, legible diagrams (drawn text is checked at 8.5px minimum), and both light and
dark schemes honoured from the system preference. Keyboard navigation and focus handling exist on
route changes but have not been audited.
