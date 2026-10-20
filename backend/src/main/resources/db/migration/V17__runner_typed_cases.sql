-- What the code runner needs for a coding problem (runner design §3): the method signature and how
-- results compare, and each case's typed arguments and return value, hidden cases included. The
-- bundle has always carried these; until now the loader kept only the display strings.
--
-- No content-hash reset (unlike V5): coding units already loaded are filled in by the loader at the
-- next real content release, without a version bump, so "What changed" does not list 142 units.
alter table test_case
    add column args           jsonb,
    add column expected_value jsonb;

create table coding_fixture (
    unit_id   text  primary key references unit on delete cascade,
    signature jsonb not null,
    compare   text  not null default 'exact' check (compare in ('exact', 'any-order'))
);
