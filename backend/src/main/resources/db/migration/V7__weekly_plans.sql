-- Weekly plans, and each learner's own time weights.
--
-- A plan is generated the first time its week is opened, from the curriculum and progress at that
-- moment, and then stays fixed for the week (proposal F10: a release mid-week changes next week,
-- never this one). Whether an item is done is not stored: it is done when the learner recorded an
-- attempt on that unit during the week, the same source of truth as reviews.

create table week_plan (
    id              bigint generated always as identity primary key,
    learner_id      bigint      not null references learner on delete cascade,
    -- The Monday the week starts, in India time.
    week_start      date        not null check (extract(isodow from week_start) = 1),
    planned_minutes int         not null check (planned_minutes >= 0),
    goal_minutes    int         not null check (goal_minutes >= 0),
    -- Grows or shrinks the week after two unusually full or empty ones (proposal F7).
    load_factor     numeric(4, 3) not null default 1,
    release_id      bigint      references curriculum_release,
    planner_version smallint    not null,
    -- Domain shares and anything the planner had to leave out, for the "why this week" view.
    rationale       jsonb       not null default '{}',
    created_at      timestamptz not null default now(),
    unique (learner_id, week_start)
);

create table plan_item (
    id         bigint generated always as identity primary key,
    plan_id    bigint   not null references week_plan on delete cascade,
    -- No cascade: units are retired, never deleted.
    unit_id    text     not null references unit,
    day        smallint not null check (day between 1 and 7),
    kind       text     not null check (kind in ('learn', 'review')),
    minutes    smallint not null check (minutes > 0),
    reason     text     not null,
    sort_order smallint not null
);

create index plan_item_plan_idx on plan_item (plan_id, day, sort_order);

-- A learner's own time weight for a domain, replacing the curriculum's default (proposal C5).
-- Only overrides are stored; a domain without a row uses domain.weight.
create table learner_weight (
    learner_id bigint   not null references learner on delete cascade,
    domain_id  text     not null references domain,
    weight     smallint not null check (weight between 0 and 100),
    primary key (learner_id, domain_id)
);
