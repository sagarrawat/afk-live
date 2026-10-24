--liquibase formatted sql

--changeset afklive:105-site-funnel
--comment: Homepage measurement without analytics: one counter per day and step (homepage view, "Start free" click),
--comment: bumped by the public site's beacon. No visitor ids, no cookies, no IP or user agent stored. New table: the
--comment: previous release ignores it.
CREATE TABLE site_funnel_days (
    day DATE NOT NULL,
    step VARCHAR(20) NOT NULL,
    count BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (day, step)
);

--changeset afklive:106-users-created-at
--comment: When an account was made, for the funnel's sign-up step. Existing accounts stay NULL (we don't know);
--comment: new rows get now() from the default, including rows the previous release inserts, so it's expand-only.
ALTER TABLE users ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ALTER COLUMN created_at SET DEFAULT now();
CREATE INDEX idx_users_created_at ON users(created_at);
