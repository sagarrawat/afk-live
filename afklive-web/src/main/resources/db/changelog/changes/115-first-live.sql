--liquibase formatted sql

--changeset afklive:115-first-live
--comment: ON-8 / GR-18. Expand only: the previous release never reads or writes these, and the User entity doesn't
--comment: map them (so a stale entity save can never clear them; they change only through conditional updates).
--comment: users.first_live_at: when a platform first received one of the account's streams (RE-8 "On"), set once
--comment: by the web's first-live sweep; the activation metric. users.bonus_days: free days earned through invites
--comment: (GR-18) while on no paid plan, added to the next paid period. The partial index serves the sweep's lookup.
ALTER TABLE users ADD COLUMN first_live_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN bonus_days INT NOT NULL DEFAULT 0;
CREATE INDEX idx_stream_events_reached ON stream_events(job_id, at) WHERE kind = 'REACHED';
