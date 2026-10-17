-- Laptop labs (content repository: labs/<unit-id>/). A lab arrives inside its unit in the bundle, so it
-- changes only when the unit does, like a coding problem's test cases. salt and token_hash never leave
-- the server: a learner's pasted token is checked here.
create table lab (
    unit_id    text   primary key references unit on delete cascade,
    title      text   not null,
    minutes    int    not null,
    needs      text[] not null,
    readme     text   not null,
    files      jsonb  not null,
    salt       text   not null,
    token_hash text   not null
);

-- A learner proved a lab with its token. Not an attempt: only a rating marks a unit done, schedules
-- reviews or earns stars, so this table is read only to say "Lab done".
create table lab_result (
    learner_id bigint      not null references learner on delete cascade,
    unit_id    text        not null references unit on delete cascade,
    done_at    timestamptz not null default now(),
    primary key (learner_id, unit_id)
);
