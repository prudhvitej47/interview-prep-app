-- Weeks a learner has said in advance they will not study (travel, a release crunch). A break week
-- gets no plan and neither counts towards nor breaks the streak (proposal G).
--
-- Stars, streaks and freezes have no tables: they are worked out from attempts, weekly plans and
-- these rows every time they are shown, so they can always be rebuilt and never drift. Undoing a
-- mis-clicked attempt therefore also takes back its star, which is the point of undo.
create table planned_break (
    learner_id bigint      not null references learner on delete cascade,
    week_start date        not null check (extract(isodow from week_start) = 1),
    created_at timestamptz not null default now(),
    primary key (learner_id, week_start)
);
