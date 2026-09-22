-- A draft sent to the curriculum: the content-repository branch it went to. A sent draft is kept
-- as a record, but no longer edited: changes from then on belong in the pull request.
alter table evidence_draft
    add column sent_at timestamptz,
    add column sent_branch text;
