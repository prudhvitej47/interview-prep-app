-- A bundle's digest identifies its content, and content can legitimately come back: revert a bad
-- change to the curriculum and it returns to a bundle it has been before. V1 made the digest
-- unique, which would reject that release and leave the database holding the reverted content.
--
-- "Already loaded" now means "the same as the latest release", which the loader checks, rather
-- than "ever seen before".
alter table curriculum_release drop constraint curriculum_release_bundle_digest_key;
