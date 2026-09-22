-- Interview evidence written in the app: a debrief after a real interview, or a report found
-- somewhere else. A draft is personal and stays here; the learner exports it as an evidence file
-- for the content repository, where it is reviewed like everything else (proposal D4). Only the
-- exported file ever leaves the database, and never the private notes.
create table evidence_draft (
    id          bigint generated always as identity primary key,
    learner_id  bigint      not null references learner on delete cascade,
    kind        text        not null check (kind in ('debrief', 'report')),
    -- The evidence record in the content repository's shape (evidence.schema.json, minus id and
    -- state), plus "private_notes", which export leaves out.
    body        jsonb       not null,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create index evidence_draft_learner_idx on evidence_draft (learner_id, updated_at desc);

-- The interview round types (taxonomy/rounds.yaml), so the forms can offer them.
create table round_type (
    id   text not null primary key,
    name text not null
);

-- Which round of its report each item came from, so two coding rounds in a row stay two rounds.
alter table evidence_item add column round_index smallint not null default 0;

-- Both are filled by the loader, which only runs when the bundle changes. Forgetting the latest
-- release's hash makes the next start load it again: evidence and round types are rewritten,
-- units are untouched (their own hashes match), and the release it records changes no units, so
-- the dashboard keeps showing the last release that did. No-op on an empty database.
update curriculum_release set bundle_digest = 'reload-evidence'
    where id = (select max(id) from curriculum_release);
