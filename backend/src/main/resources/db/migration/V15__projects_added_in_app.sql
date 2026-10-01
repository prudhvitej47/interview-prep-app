-- Projects and questions can now be added and changed in the app, as well as imported from a file.
--
-- A file speaks only for what it brought. An import still hides an imported project or question the
-- new file no longer lists, but never one the learner added in the app: an older file never knew it,
-- and the learner's own question vanishing because they re-imported would be a surprise with no clue.
alter table experience_project add column added_in_app boolean not null default false;
alter table project_question   add column added_in_app boolean not null default false;
