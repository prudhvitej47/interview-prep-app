-- A learner's code for a coding unit (runner design §2): one row per learner per unit, saved explicitly
-- or before a run. Last submit is kept for a revisit ("last submit: 11/14"). Runs are never attempts:
-- only the rating marks a unit done, so nothing here feeds progress, stars or reviews.
create table code_draft (
    learner_id         bigint      not null references learner on delete cascade,
    unit_id            text        not null references unit on delete cascade,
    code               text        not null,
    saved_at           timestamptz not null default now(),
    last_submit_passed int,
    last_submit_total  int,
    last_submit_at     timestamptz,
    primary key (learner_id, unit_id)
);
