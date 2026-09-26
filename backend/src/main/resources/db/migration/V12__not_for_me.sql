-- Topics and units a learner has said are not for them. The planner leaves them out of that learner's
-- plans, for new learning and for reviews; the other learner's plans are untouched. Removing the row
-- brings them back. A topic covers every unit under it and under its subtopics.
create table learner_exclusion (
    learner_id  bigint      not null references learner on delete cascade,
    scope       text        not null check (scope in ('topic', 'unit')),
    scope_id    text        not null,
    created_at  timestamptz not null default now(),
    primary key (learner_id, scope, scope_id)
);
