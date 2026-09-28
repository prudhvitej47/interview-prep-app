-- Questions about each learner's own projects, their answers and how each rehearsal went.
--
-- None of this comes from the content repository: a learner imports a question file of their own
-- through the app, because career details never go into Git. It lives here and in the database
-- backups, and nowhere else.

-- ---------------------------------------------------------------------------
-- Projects: the V1 table, unused until now
-- ---------------------------------------------------------------------------

-- The key is the project's id in the import file. Re-importing matches on it, so a project can be
-- renamed without losing its questions. Any row from before this migration gets a key made from its
-- id (there are none in practice: nothing wrote this table).
alter table experience_project
    add column key        text,
    add column sort_order integer not null default 0,
    -- A project missing from a re-import is hidden, never deleted: its answers stay, and it comes
    -- back as it was if a later file brings it back.
    add column retired    boolean not null default false;

update experience_project set key = 'project-' || id where key is null;

alter table experience_project alter column key set not null;
alter table experience_project add constraint experience_project_learner_key unique (learner_id, key);

-- The key is the identity now. A unique name would make a re-import that swaps two projects' names,
-- or reuses a retired project's name, fail halfway for no reason the learner could see.
alter table experience_project drop constraint experience_project_learner_id_name_key;

-- ---------------------------------------------------------------------------
-- The questions an interviewer asks about a project, and the learner's own answer to each
-- ---------------------------------------------------------------------------

create table project_question (
    id             bigint generated always as identity primary key,
    project_id     bigint      not null references experience_project on delete cascade,
    -- Stable across re-imports, like the project's key: a reworded prompt updates in place and keeps
    -- the answer and the ratings.
    key            text        not null,
    -- The deep-dive ladder, plus a behavioural angle on the same project.
    rung           text        not null check (rung in
                       ('walkthrough', 'why', 'scale', 'failure', 'change', 'story')),
    prompt         text        not null,
    -- The follow-ups an interviewer pushes with, and what a strong answer covers. Markdown. Both are
    -- hidden on the page until the learner has answered or rated the question.
    probes         text        not null default '',
    strong_answer  text        not null default '',
    -- Curriculum units that teach the idea underneath. No foreign key: content evolves, and a unit
    -- id that has gone is dropped at the next import rather than blocking it.
    unit_ids       text[]      not null default '{}',
    minutes        smallint    not null default 15 check (minutes between 1 and 120),
    sort_order     integer     not null default 0,
    -- Missing from the latest import: hidden everywhere, kept with its answer and ratings.
    retired        boolean     not null default false,
    -- The learner's own answer, autosaved from the page. The import never touches it.
    answer         text        not null default '',
    answered_at    timestamptz,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    unique (project_id, key)
);

-- ---------------------------------------------------------------------------
-- Rehearsals
-- ---------------------------------------------------------------------------

-- Like `attempt`, and kept apart from it on purpose: a project question is not a unit, and mixing the
-- two would inflate "units done" and the coverage figures. Append-only; when a question is next due
-- is worked out from these rows with the same review schedule units use. Undo removes the latest row.
create table project_attempt (
    id           bigint generated always as identity primary key,
    learner_id   bigint      not null references learner on delete cascade,
    question_id  bigint      not null references project_question on delete cascade,
    rating       text        not null check (rating in ('again', 'hard', 'good', 'easy')),
    created_at   timestamptz not null default now()
);

create index project_attempt_learner_question_idx on project_attempt (learner_id, question_id, created_at);
