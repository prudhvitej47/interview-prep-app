-- A week's plan can hold a question about one of the learner's own projects, beside units.
--
-- A plan item is about exactly one thing: a unit (learn or review) or a project question (kind
-- 'project'). Like units, whether a project item is done is not stored: it is done when the
-- learner rated that question during the week.

alter table plan_item alter column unit_id drop not null;

-- No cascade and no "set null", on purpose. Questions are retired, never deleted, just as units
-- are, so a plan's history keeps pointing at what was planned. "set null" would break the rule
-- below (an item about nothing) and fail anyway; a cascade would silently rewrite past weeks' done
-- minutes and so the streak. The only real delete path is removing a learner, which also removes
-- their plans in the same statement, and a NO ACTION key is checked at the end of the statement, so
-- that still works.
alter table plan_item add column project_question_id bigint references project_question;

alter table plan_item drop constraint plan_item_kind_check;
alter table plan_item add constraint plan_item_kind_check check (kind in ('learn', 'review', 'project'));

alter table plan_item add constraint plan_item_one_subject
    check (num_nonnulls(unit_id, project_question_id) = 1);
alter table plan_item add constraint plan_item_project_kind
    check ((kind = 'project') = (project_question_id is not null));

-- For the foreign key's check when a question row is deleted, and for "was it planned" lookups.
create index plan_item_project_question_idx on plan_item (project_question_id)
    where project_question_id is not null;
