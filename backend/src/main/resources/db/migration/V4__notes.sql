-- A learner's own notes on a unit: working, reminders, and their answers to a project deep-dive
-- ladder. The ladder itself is shared curriculum in Git; what a learner writes about their own
-- systems is personal, so it lives here and never in the content repository.
create table note (
    learner_id bigint      not null references learner on delete cascade,
    unit_id    text        not null references unit on delete cascade,
    body       text        not null,
    updated_at timestamptz not null default now(),
    primary key (learner_id, unit_id)
);
