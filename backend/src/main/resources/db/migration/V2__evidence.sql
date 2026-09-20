-- Interview evidence: the dated, sourced reports that justify what is in the curriculum.
--
-- Mirrors evidence/*.yaml in the content repo, which is upserted the same way units are. Every
-- question here is a paraphrase — the content repo forbids copying a reported question verbatim.

create table company (
    id       text   not null primary key,
    name     text   not null,
    category text   not null check (category in
                 ('none', 'mnc', 'fintech', 'data', 'ai', 'startup-practice')),
    aliases  text[] not null default '{}'
);

comment on column company.category is
    'startup-practice companies are tagged as practice loops only, never as targets.';

create table evidence (
    id             text not null primary key,
    company_id     text not null references company,
    role           text,
    level          text,
    location       text,
    -- Reports often give only a year or a month, so these are stored as written, not as dates.
    interview_date text,
    report_date    text,
    source_kind    text not null check (source_kind in
                       ('candidate-report', 'official-guide', 'prep-guide', 'news',
                        'curated-bank', 'first-hand', 'user-provided')),
    source_title   text not null,
    source_url     text,
    source_publisher text,
    accessed       date not null,
    tier           text not null check (tier in ('primary', 'secondary')),
    outcome        text check (outcome in
                       ('offer', 'no-offer', 'downlevelled', 'declined', 'unknown',
                        'not-applicable')),
    notes          text,
    state          text not null check (state in ('proposed', 'accepted', 'rejected')),
    release_id     bigint not null references curriculum_release,
    updated_at     timestamptz not null default now()
);

comment on column evidence.tier is
    'primary = the company''s own guide or a first-hand debrief; secondary = a third-party report.';

create index evidence_company_idx on evidence (company_id) where state = 'accepted';

-- One paraphrased question, and the topics it maps to. A question can touch several topics, so
-- the topic link is its own table rather than an array: the planner joins on it constantly.
create table evidence_item (
    id          bigint generated always as identity primary key,
    evidence_id text     not null references evidence on delete cascade,
    round_type  text     not null,
    summary     text,
    question    text,
    sort_order  smallint not null default 0
);

create index evidence_item_evidence_idx on evidence_item (evidence_id, sort_order);

create table evidence_item_topic (
    item_id    bigint not null references evidence_item on delete cascade,
    topic_id   text   not null references topic,
    confidence numeric(3, 2) check (confidence between 0 and 1),
    primary key (item_id, topic_id)
);

create index evidence_item_topic_topic_idx on evidence_item_topic (topic_id);

-- ---------------------------------------------------------------------------
-- Proposed changes, and what each learner decided to do with them
-- ---------------------------------------------------------------------------

create table change_proposal (
    id           bigint generated always as identity primary key,
    release_id   bigint references curriculum_release,
    title        text        not null,
    summary      text,
    pr_url       text,
    status       text        not null check (status in ('pending', 'approved', 'rejected')),
    created_at   timestamptz not null default now()
);

-- Where a new unit lands for one learner: the coming week by default, the current week if it
-- displaces something, or parked at the end of the track.
create table placement (
    id          bigint generated always as identity primary key,
    learner_id  bigint   not null references learner on delete cascade,
    unit_id     text     not null references unit on delete cascade,
    release_id  bigint   not null references curriculum_release,
    choice      text     not null check (choice in ('now', 'next-week', 'end-of-track')),
    target_week date,
    decided_at  timestamptz not null default now(),
    unique (learner_id, unit_id, release_id)
);

create index placement_learner_idx on placement (learner_id, choice);
