-- Every time a learner finishes or reviews a unit and says how it went. Append-only: whether a unit
-- is done, and when it is next due for review, are worked out from these rows, never stored beside
-- them, so the two can never disagree. Undo removes the latest row.
--
-- Rows are only ever written by an explicit "how did it go?" click. Opening a unit, reading its
-- solution or running a query records nothing.
create table attempt (
    id          bigint generated always as identity primary key,
    learner_id  bigint      not null references learner on delete cascade,
    -- No cascade: units are retired, never deleted, and history must keep pointing at them.
    unit_id     text        not null references unit,
    rating      text        not null check (rating in ('again', 'hard', 'good', 'easy')),
    created_at  timestamptz not null default now()
);

create index attempt_learner_unit_idx on attempt (learner_id, unit_id, created_at);
