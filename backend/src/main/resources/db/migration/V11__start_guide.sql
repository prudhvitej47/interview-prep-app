-- When the learner closed the home page's "Start here" guide. Kept on the server, not in the
-- browser, so it stays closed on every device and every sign-in; null means it still shows.
alter table learner
    add column start_guide_closed_at timestamptz;
