-- Practice fixtures: the loader now fills test_case and sql_fixture from each unit's tests file.
--
-- The expected result of a SQL problem is not stored. The browser computes it by running the
-- reference query in PGlite, as the content repository's CI does when it checks the unit's
-- Expected output table, so there is one answer and nothing to drift.
alter table sql_fixture
    drop column expected_result,
    add column order_matters boolean not null default false;

-- Coding and SQL units loaded before this migration have no fixtures, and a bundle whose hash is
-- unchanged is skipped. Forgetting those hashes, and the latest release's, makes the next load
-- rewrite exactly those units (they become version 2 in a new release). No-op on an empty database.
update unit set content_hash = 'refixture' where type in ('coding', 'sql');
update curriculum_release set bundle_digest = 'refixture'
    where id = (select max(id) from curriculum_release);
