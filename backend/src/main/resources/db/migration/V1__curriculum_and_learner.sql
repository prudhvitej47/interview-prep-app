-- Curriculum content (loaded from interview-prep-content) and the learners it is planned for.
--
-- Content tables are keyed by the stable text id the content repo uses, e.g. 'dsa.sliding-window'
-- or 'ds.transactions.idempotency-keys'. Those ids are permanent by content-repo rule, so progress
-- and plans can point at them directly and survive any edit, move or retitle of the content.

-- ---------------------------------------------------------------------------
-- Learners
-- ---------------------------------------------------------------------------

create table learner (
    id             bigint generated always as identity primary key,
    slug           text        not null unique,
    display_name   text        not null,
    email          text unique,
    target_level   text,
    target_date    date,
    hours_per_week numeric(4, 1) check (hours_per_week > 0),
    -- ISO day numbers, 1 = Monday. The planner places heavy units on the long days.
    study_days     smallint[]  not null default '{}',
    created_at     timestamptz not null default now()
);

comment on table learner is 'One row per person studying. Never more than a handful.';

-- A prep cycle can be paused for months and resumed; streaks belong to a cycle, stars are lifetime.
create table prep_cycle (
    id               bigint generated always as identity primary key,
    learner_id       bigint      not null references learner on delete cascade,
    started_on       date        not null,
    ended_on         date,
    status           text        not null check (status in ('active', 'paused', 'closed')),
    targets_snapshot jsonb       not null default '{}',
    created_at       timestamptz not null default now(),
    check (ended_on is null or ended_on >= started_on)
);

create index prep_cycle_learner_idx on prep_cycle (learner_id, status);

-- Only one active cycle per learner; paused and closed ones accumulate as history.
create unique index prep_cycle_one_active_per_learner
    on prep_cycle (learner_id) where status = 'active';

-- ---------------------------------------------------------------------------
-- Curriculum: domains and topics, mirroring taxonomy/domains.yaml
-- ---------------------------------------------------------------------------

create table domain (
    id         text        not null primary key,
    name       text        not null,
    weight     smallint    not null check (weight between 0 and 100),
    sort_order smallint    not null,
    updated_at timestamptz not null default now()
);

comment on column domain.weight is
    'Starting share of study time, as a percent. The 12 domains sum to 100; the planner shifts
     these by weakness, target companies and upcoming interviews without changing this column.';

-- Topics nest one level (topic -> subtopic) through parent_id. A topic id does NOT contain its
-- domain id: domain 'databases' owns topics prefixed 'db.', 'distributed' owns 'ds.', and so on,
-- which is why the link is a column and never string manipulation.
create table topic (
    id         text        not null primary key,
    domain_id  text        not null references domain,
    parent_id  text references topic,
    name       text        not null,
    sort_order smallint    not null,
    updated_at timestamptz not null default now(),
    check (parent_id is null or parent_id <> id)
);

create index topic_domain_idx on topic (domain_id, sort_order);
create index topic_parent_idx on topic (parent_id);

-- ---------------------------------------------------------------------------
-- Curriculum releases: every approved change set to the content repo
-- ---------------------------------------------------------------------------

create table curriculum_release (
    id              bigint generated always as identity primary key,
    version         text        not null unique,
    bundle_digest   text        not null unique,
    git_sha         text        not null,
    pr_url          text,
    changelog       text,
    units_added     integer     not null default 0,
    units_changed   integer     not null default 0,
    units_retired   integer     not null default 0,
    created_at      timestamptz not null default now()
);

comment on column curriculum_release.version is 'Year.week.sequence, e.g. 2026.38.1.';
comment on column curriculum_release.bundle_digest is
    'Digest of the published content bundle. Unique, so re-loading an unchanged bundle cannot
     create a second release.';

-- ---------------------------------------------------------------------------
-- Curriculum: units and what hangs off them
-- ---------------------------------------------------------------------------

create table unit (
    id           text        not null primary key,
    topic_id     text        not null references topic,
    type         text        not null check (type in
                     ('concept', 'question', 'coding', 'sql', 'lld', 'hld', 'scenario',
                      'project', 'behavioral')),
    title        text        not null,
    difficulty   smallint    not null check (difficulty between 1 and 5),
    est_minutes  smallint    not null check (est_minutes between 5 and 240),
    rounds       text[]      not null default '{}',
    technologies text[]      not null default '{}',
    origin       text        not null check (origin in
                     ('original-practice', 'synthesized', 'evidence-derived')),
    -- 'shared', or 'learner:<slug>' for content only one person should see.
    visibility   text        not null default 'shared',
    state        text        not null check (state in ('draft', 'reviewed', 'retired')),
    version      integer     not null check (version >= 1),
    body         jsonb       not null,
    content_hash text        not null,
    release_id   bigint      not null references curriculum_release,
    updated_at   timestamptz not null default now(),
    check (visibility = 'shared' or visibility like 'learner:%')
);

comment on table unit is
    'Upserted by the loader, keyed by the content repo''s stable id. Units are never deleted, only
     moved to state = retired, so attempts and plan items from months ago stay meaningful.';

create index unit_topic_idx on unit (topic_id) where state <> 'retired';
create index unit_type_idx on unit (type) where state <> 'retired';
create index unit_visibility_idx on unit (visibility) where visibility <> 'shared';

create table unit_prereq (
    unit_id   text not null references unit on delete cascade,
    prereq_id text not null references unit on delete cascade,
    primary key (unit_id, prereq_id),
    check (unit_id <> prereq_id)
);

-- Where a unit's claims come from: a book chapter, a blog post, an evidence record.
create table source (
    id        bigint generated always as identity primary key,
    kind      text not null check (kind in
                  ('book', 'official-doc', 'engineering-blog', 'candidate-report',
                   'prep-guide', 'news', 'forum', 'evidence', 'original')),
    title     text not null,
    url       text,
    locator   text,
    published text,
    accessed  date,
    unique nulls not distinct (kind, title, url, locator)
);

comment on column source.locator is 'Chapter and page for a book, so nothing is ever copied blind.';

create table unit_source (
    unit_id   text   not null references unit on delete cascade,
    source_id bigint not null references source,
    primary key (unit_id, source_id)
);

create table visual (
    id          bigint generated always as identity primary key,
    unit_id     text not null references unit on delete cascade,
    kind        text not null check (kind in ('mermaid', 'svg', 'visualizer')),
    body        text not null,
    sort_order  smallint not null default 0,
    reviewed_at timestamptz
);

create index visual_unit_idx on visual (unit_id, sort_order);

-- Coding problems: visible examples plus hidden cases the learner cannot read ahead.
create table test_case (
    id         bigint generated always as identity primary key,
    unit_id    text     not null references unit on delete cascade,
    name       text,
    input      text     not null,
    expected   text     not null,
    hidden     boolean  not null default false,
    sort_order smallint not null default 0
);

create index test_case_unit_idx on test_case (unit_id, sort_order);

-- SQL problems: schema and seed rows shipped to the browser, where PGlite runs them.
create table sql_fixture (
    unit_id         text not null primary key references unit on delete cascade,
    schema_ddl      text not null,
    seed_sql        text not null,
    expected_result jsonb,
    reference_query text not null
);

-- ---------------------------------------------------------------------------
-- Tracks: ordered paths that cut across domains
-- ---------------------------------------------------------------------------

create table track (
    id          text not null primary key,
    title       text not null,
    description text,
    updated_at  timestamptz not null default now()
);

create table track_unit (
    track_id    text     not null references track on delete cascade,
    module_id   text     not null,
    unit_id     text     not null references unit on delete cascade,
    sort_order  smallint not null,
    primary key (track_id, module_id, unit_id)
);

create index track_unit_order_idx on track_unit (track_id, sort_order);

-- ---------------------------------------------------------------------------
-- What each learner brings to the curriculum
-- ---------------------------------------------------------------------------

-- Sparse on purpose. Onboarding writes 12 rows (one per domain); a topic row appears only when
-- someone rates that topic specifically. Everything else is resolved at read time:
--   real progress  ->  this topic's rating  ->  its domain's rating  ->  2.5 neutral
-- so topics added by a later content release inherit a sane value with no backfill, and never
-- look maximally urgent to the planner just because nobody has rated them yet.
create table learner_competency (
    learner_id bigint      not null references learner on delete cascade,
    scope      text        not null check (scope in ('domain', 'topic')),
    scope_id   text        not null,
    rating     smallint    not null check (rating between 0 and 5),
    source     text        not null check (source in ('self-rating', 'diagnostic')),
    rated_at   timestamptz not null default now(),
    primary key (learner_id, scope, scope_id)
);

-- A learner's own systems, turned into deep-dive questions. The generic question ladder lives in
-- the content repo as a shared unit; the answers and the project details live only here, because
-- career history never goes into Git.
create table experience_project (
    id          bigint generated always as identity primary key,
    learner_id  bigint      not null references learner on delete cascade,
    name        text        not null,
    summary     text,
    notes       text,
    topic_ids   text[]      not null default '{}',
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    unique (learner_id, name)
);
