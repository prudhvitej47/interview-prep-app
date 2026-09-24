---
name: Interview Prep
description: A study bench for two engineers - plain surfaces, exact controls, colour only when it reports state.
colors:
  ink: "#232321"
  paper: "#fbfaf8"
  ink-dark: "#eceae5"
  paper-dark: "#1c1c1a"
  muted: "#6b6a66"
  muted-dark: "#9b998f"
  progress: "#16a34a"
  attention: "#d97706"
  wrong: "#dc2626"
  code-literal: "#2f5d8a"
  code-literal-dark: "#9fbcdd"
typography:
  display:
    fontFamily: "ui-sans-serif, system-ui, -apple-system, Segoe UI, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 700
    lineHeight: 1.6
    letterSpacing: "-0.01em"
  title:
    fontFamily: "ui-sans-serif, system-ui, -apple-system, Segoe UI, sans-serif"
    fontSize: "1.17rem"
    fontWeight: 600
    lineHeight: 1.4
  body:
    fontFamily: "ui-sans-serif, system-ui, -apple-system, Segoe UI, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.6
  label:
    fontFamily: "ui-sans-serif, system-ui, -apple-system, Segoe UI, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.5
  data:
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace"
    fontSize: "0.9rem"
    fontWeight: 400
    lineHeight: 1.5
rounded:
  control: "6px"
  surface: "8px"
  pill: "999px"
  bar: "4px"
spacing:
  hair: "0.2rem"
  tight: "0.4rem"
  snug: "0.6rem"
  base: "1rem"
  section: "1.6rem"
components:
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.paper}"
    rounded: "{rounded.control}"
    padding: "0.5rem 1.1rem"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "0.5rem 1.1rem"
  choice-pill:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    rounded: "{rounded.pill}"
    padding: "0.3rem 0.7rem"
    typography: "{typography.label}"
  choice-pill-chosen:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.paper}"
    rounded: "{rounded.pill}"
    padding: "0.3rem 0.7rem"
  nav-link:
    textColor: "{colors.muted}"
    rounded: "{rounded.pill}"
    padding: "0.2rem 0.6rem"
  nav-link-active:
    textColor: "{colors.ink}"
    rounded: "{rounded.pill}"
    padding: "0.2rem 0.6rem"
  card:
    backgroundColor: "color-mix(in srgb, {colors.ink} 5%, transparent)"
    textColor: "{colors.ink}"
    rounded: "{rounded.surface}"
    padding: "0.8rem 1rem"
  field:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "0.6rem"
---

# Design System: Interview Prep

## Overview

**Creative North Star: "The Engineer's Bench"**

Tools laid out and labelled, within reach. This interface is instrumentation for two people doing
difficult work on a deadline: it reports what is due, what is done and what is left, and then gets
out of the way of the material. Density appears only where density helps — a week's plan, a table of
trade-offs, a set of test cases. Everywhere else the page is plain, and that plainness is the design,
not an absence of one.

The system is built from three variables and a single stylesheet. Two of them are the ground (near-black
ink on warm paper, inverted after dark) and one is the recessive voice for everything secondary. Every
surface in the app is that same ink mixed into the background at 4–6% — never a new colour, never a
shadow. Depth is tonal and shallow by construction: a card is a slightly warmer patch of the same
paper, not an object floating above it. Saturated colour exists in exactly three places and always
reports a state rather than setting a mood.

The intended growth is in **typography, structure and the figures** — a sharper scale, stronger section
rhythm, better-composed diagrams and data. It is not in colour, ornament or motion. Confirmed
anti-references: this must never read as a SaaS dashboard (gradient cards, coloured stat tiles, an icon
on every heading), a marketing page (hero, social proof, feature grid), or a documentation site
(three-column shell, persistent left nav, right-hand table of contents).

**Key Characteristics:**

- Three colour variables, one stylesheet, no component framework
- Flat by construction: tonal layering, zero `box-shadow` in the entire system
- Colour reports state; it never decorates
- One 44rem column, from phone to desktop
- Monospace only where the content is data
- Nothing animates

## Colors

A near-monochrome ground in warm greys, with three saturated colours that are allowed to speak only
about state.

### Primary

- **Ink** (`#232321` light / `#eceae5` dark): all body text, headings, links, the fill of the one
  solid button per view, and — mixed into the background at 4–15% — every surface, border and rule in
  the app. This one value carries almost the whole interface.

### Secondary

- **Progress Green** (`#16a34a`): the week's goal bar and share bars, and the "right" verdict after a
  practice attempt. It means work has been done or an answer is correct, and nothing else.
- **Attention Amber** (`#d97706`, at 12–18% mix): the "how are you with these?" rating card, and the
  banner on a retired unit. It asks for a moment of attention; it never signals an error.
- **Wrong Red** (`#dc2626`, at 15% mix): one use only, the verdict on an incorrect practice answer.
- **Literal Blue** (`#2f5d8a` light / `#9fbcdd` dark): the one hue in the system that does not report
  state, and the only one confined to a single surface. It colours literal values — strings,
  numbers, `true`/`false`/`null` — inside a highlighted code block, and appears nowhere else. It is
  blue precisely so it cannot be mistaken for progress, attention or wrong.

### Neutral

- **Paper** (`#fbfaf8` light / `#1c1c1a` dark): the page ground. Warm, not white — closer to paper
  than to a screen.
- **Muted** (`#6b6a66` light / `#9b998f` dark): secondary prose, hints, metadata, breadcrumbs,
  inactive navigation, and the count beside a title. Anything the reader may skip.

### Named Rules

**The State-Only Rule.** Saturated colour reports state and nothing else: green for progress and
correct, amber for attention wanted, red for wrong. There is no brand hue, no fourth colour, and no
colour used because a section needed livening up.

**The Quiet Highlighter Rule.** A code block is marked up with weight and quietness before colour.
Comments drop to `--muted` and italic; keywords, annotations and configuration keys take
`font-weight: 600` and keep the ink they already had; only literal values take Literal Blue, and
that hue exists nowhere outside a `<pre>`. An off-the-shelf highlighter theme is never used, because
its green keyword and red error would read as this app's "done" and "wrong".

**The One Ink Rule.** Surfaces, borders, rules, bars and code blocks are all `color-mix` of the
foreground into the background — 4% for a diagram frame, 5% for a card, 6% for code, 10–12% for a
rule, 15–25% for a border. New surfaces pick a percentage from that ladder rather than a new colour.

## Typography

**Display Font:** the system UI stack (`ui-sans-serif, system-ui, -apple-system, "Segoe UI",
sans-serif`)
**Body Font:** the same stack — one family throughout
**Data Font:** `ui-monospace, SFMono-Regular, Menlo, monospace`

**Character:** deliberately unstyled. The system face is the one typeface that never needs
downloading, never blocks first paint, and never fails on a device — which matters when the app must
work over a private network on a small machine with no third-party requests. Personality is carried by
rhythm and contrast, not by the letterforms.

### Hierarchy

- **Display** (700, 1.5rem, −0.01em): the app title only, in the header, linking home.
- **Headline** (browser default `h2`, ~1.5rem): the page's own title — a unit name, a week, a report.
- **Title** (600, ~1.17rem `h3`, 1.6rem top margin): section headings inside a unit and card headings.
- **Body** (400, 16px/1.6): prose. The 44rem column holds it near 70 characters.
- **Label** (400, 0.875rem, muted): hints, metadata, breadcrumbs, counts, status lines.
- **Data** (monospace, 0.9rem): SQL and code the learner types, and rendered code blocks.

### Named Rules

**The Muted-Paragraph Rule.** A bare `<p>` is muted by default, because most paragraphs in this app
are supporting text. A page whose job is *reading* — a unit, How this works — opts back into full
contrast explicitly. If new prose looks washed out, the page is missing that opt-in.

**The One Column Rule.** 44rem, centred, at every width. There is no sidebar, no second column and no
right-hand table of contents. Navigation is a row of pills under the title.

## Layout

One centred column of `max-width: 44rem` inside 2rem/1rem body padding, unchanged from phone to
desktop; the column simply gets narrower. There is no grid system. Where a row needs structure it uses
a local `grid-template-columns` with `minmax(0, 1fr)` so long titles shrink instead of pushing
controls away — the week's placement rows and the previous/next plan strip both do this, and both
collapse to a single column at 560px.

Spacing is a small informal ladder in rem: 0.2 hairline, 0.4 tight, 0.6 snug, 1 base, 1.6 between
sections. Vertical rhythm comes from that ladder plus 1px rules at 10–12% ink; there is no baseline
grid.

Two responsive rules carry most of the burden: the `.placements` and `.plan-nav` grids collapse below
560px, and a diagram never shrinks below 640px — it scrolls inside its own frame instead, because a
scaled-down Mermaid label becomes unreadable before it becomes small. `tools/layout-check.mjs` enforces
that at 390px and 1000px in both schemes, failing any drawn diagram text under 8.5px.

## Elevation & Depth

**There are no shadows in this system.** Not one `box-shadow` exists in the stylesheet, and none
should be added. Depth is entirely tonal: a surface is the foreground colour mixed into the background
at a low percentage, so it reads as a warmer patch of the same paper rather than an object above it.

The ladder, in ascending prominence: diagram frame 4%, card and panel 5%, code block 6%, bar track and
list rule 10%, section rule 12%, table border 15%, input border 20%, control border 25%, ghost button
border 30%.

### Named Rules

**The Flat Bench Rule.** Surfaces never lift. If something needs to stand out, it gets a rule, a
tonal step or a state colour — never elevation, and never a gradient.

## Shapes

Three radii and nothing else: **6px** for controls (buttons, inputs, code blocks, banners), **8px** for
surfaces (cards, panels, fieldsets, diagram frames), and **999px** for pills (navigation links, choice
buttons, chips). Bars use 4px so a 0.5rem-tall track still reads as a bar rather than a lozenge.

Borders are 1px, always ink mixed into the background. One deliberate exception carries meaning: a
**dashed** border marks a folded spoiler — hints, a solution, an expected diagnosis — so a section the
reader is meant to open looks different from one that is simply grouped.

## Components

### Buttons

- **Shape:** 6px corners, `font: inherit` — a button is the same size as the text around it.
- **Primary:** solid ink on paper (`background: var(--fg); color: var(--bg)`), 0.5rem × 1.1rem
  padding, no border. One per view: save, run, submit.
- **Ghost / secondary:** transparent fill, 1px ink-at-30% border, ink text, same padding.
- **Link button:** no chrome at all — muted, underlined, 0.875rem, for "change my hours" and other
  in-flow actions that are not the page's main verb.
- **Hover:** the solid fill lightens one step toward the page (`color-mix(in srgb, var(--fg) 86%,
  var(--bg))`); an outlined control fills at 8% ink and strengthens its border to 45%.
- **Pressed:** one further step in the same direction — fill at 74%, outline fill at 14%.
- **Disabled:** `opacity: 0.45`, default cursor. No colour change, no hover.
- **Character:** tactile without motion. A control answers by changing weight, never by moving: there
  is no transition, so the feedback lands as fast as the key press.

### Choice pills

- **Style:** 999px, 0.8rem text, transparent fill with a 1px ink-at-25% border.
- **Selected:** inverts to solid ink on paper. Used for Now / Next week / Later on the "what changed"
  card, and for the four self-ratings after a unit.
- **Hover:** border to 50% ink, fill at 6%; a selected pill lightens like the primary button.

### Cards and panels

- **Corners:** 8px. **Background:** ink at 5%. **Border:** none. **Shadow:** none.
- **Padding:** 0.8rem × 1rem; heading flush to the top (`h3 { margin: 0 0 0.5rem }`).
- Used for the dashboard's cards, the rewards strip, reviews due, and the start-here guide.

### Highlighted code

A fenced block in a language the curriculum uses is marked up at build time by `lowlight` on
highlight.js's bare core, with four grammars registered by hand — **Java, SQL, YAML and
properties** — and nothing else, so no unused language and no outside request ships with the app. A
` ```text ` fence stays plain: it holds output and prose, which has no grammar to show. Rendering
produces React elements, never `innerHTML`.

The palette is deliberately almost colourless, against the highlighter convention:

| Token | Treatment | Why |
| --- | --- | --- |
| comment, quote, doctag | `var(--muted)`, italic | the one thing in a block a reader may skip, in the app's existing recessive voice |
| keyword, built-in, annotation, config key, section | `font-weight: 600`, ink unchanged | structure carried by weight, which costs no colour |
| string, number, literal, regexp, symbol | Literal Blue | the values are what a reader scans for, and they are the only thing worth a hue |
| type, class and method names | full ink | already the loudest thing on the surface |
| everything else | inherits | operators, punctuation and identifiers are the body text of code |

Contrast over the 6% code surface: 4.6:1 light / 5.2:1 dark for a comment, 5.9:1 / 7.5:1 for a
literal. Adding a language means registering its grammar *and* checking what token classes it emits
against this table — a grammar that emits something unlisted falls back to plain ink, which is
correct but dull.

### Inputs and fields

- **Style:** 6px, page background, 1px ink-at-20–25% border, `font: inherit` (monospace and 0.9rem in
  the practice editors, where the content is code).
- **Focus:** the house ring (below), plus the field's own border strengthening to 45% ink — the
  control answers in its own vocabulary as well as wearing the ring.

### Navigation

- A row of pills under the title: muted by default, underline on hover, and the current section in
  full-contrast ink with a 1px ink-at-25% border. Identical at every width — it wraps rather than
  collapsing into a menu.
- Every link carries that 1px border, transparent when it is not current, so marking a pill never
  moves the row. **Every address belongs to exactly one pill**: a unit or a topic page is the
  curriculum, which lives on the home page, so Home stays marked while one is open. A row with no
  pill at all reads as broken, not as neutral.
- Breadcrumbs are muted 0.875rem, `›`-separated, with the domain linking back to its section of the
  home page.

### Browser surfaces

The parts nobody draws still belong to the system, and browser defaults match nothing here.

- **Focus ring:** one rule for every control — `2px solid color-mix(in srgb, var(--fg) 55%,
  transparent)` at `outline-offset: 2px`, on `:focus-visible` only, so it appears for the keyboard and
  not for the mouse. `tools/layout-check.mjs` tabs through the first four controls of every page and
  fails any that shows no ring.
- **The page heading** is focused by script after every navigation, so the keyboard and a screen
  reader land on the new page rather than at the top of the window. Browsers disagree about whether
  a script focus counts as "keyboard" — WebKit draws the ring after a mouse click, Chrome does not —
  so the app records which device the reader used and marks the heading `data-pointer-nav` when it
  was the mouse; only that one element, and only then, goes without the ring. The focus itself
  always happens.
- **Selection:** ink at 18%, so selected text stays legible in both schemes.
- **Caret:** the foreground colour. **Scrollbar:** ink at 30% on a transparent track.

### Signature component: the unit page

The one surface everything else exists to serve, and the only place several systems meet at once:
prose at full contrast, tables with 15% borders, 6% code blocks, 4% diagram frames that scroll rather
than shrink, dashed folded sections for anything that would spoil an attempt, a practice panel, the
four rating buttons, and a private note editor. Its section rhythm — `h3` at 1.6rem top margin, rules
between list items — is the template for any long reading page.

### Signature component: the week's plan

Grouped by day; each row a checked or unchecked mark, the unit's title, its type and minutes in muted
label text, and the planner's one-line reason underneath. A done row goes line-through and muted. The
goal bar above is the only green on the page.

## Do's and Don'ts

### Do:

- **Do** build surfaces from `color-mix(in srgb, var(--fg) N%, transparent)`, picking N from the
  existing ladder (4 / 5 / 6 / 10 / 12 / 15 / 20 / 25 / 30).
- **Do** keep every new page inside the single 44rem column, and let it narrow rather than rearrange.
- **Do** put state in colour and nothing else: green for progress or correct, amber for attention, red
  for wrong.
- **Do** use monospace when the content is code or a query the learner types, and never for emphasis.
- **Do** collapse a two-column row to one column at 560px, using `minmax(0, 1fr)` so long titles
  shrink instead of pushing controls off the row.
- **Do** let a wide diagram scroll inside its frame below 640px, and run `tools/layout-check.mjs` over
  any page that gains one.
- **Do** mark a spoiler with the dashed-border fold; a reader must be able to see what is safe to read.
- **Do** give every new control the house focus ring by leaving `:focus-visible` alone, and let the
  control also answer in its own vocabulary (border, fill) on hover and press.

### Don't:

- **Don't** add a `box-shadow`, a gradient, or any lift. This system has none, by decision.
- **Don't** introduce a fourth colour, a brand hue, or colour used for decoration rather than state.
  Literal Blue is the single exception, it is confined to code blocks, and it is not a precedent.
- **Don't** drop in a highlighter's stock theme. Its green keyword and red error mean "done" and
  "wrong" here; mark code up with weight and quietness first, and give the one hue to literals.
- **Don't** load a web font, an icon font, or any third-party asset: the app must render fully on a
  private network with no outside requests.
- **Don't** add an icon beside a heading, a coloured stat tile, or a sidebar. Those are the SaaS
  dashboard this interface is deliberately not.
- **Don't** animate. There is no transition or keyframe in the system: a control answers by changing
  weight, not by moving, and motion needs a reason stronger than polish.
- **Don't** leave a long token to push the page sideways — inline code wraps anywhere, table cells
  break only when a word genuinely does not fit.
- **Don't** let a bare `<p>` carry important prose on a reading page without opting back into full
  contrast; muted is the default for a reason.
