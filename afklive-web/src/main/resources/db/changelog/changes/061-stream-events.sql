--liquibase formatted sql

--changeset afklive:061-stream-events
--comment: RE-9: a run's timeline: a destination reached, reconnecting (and why), not reaching, back again. The
--comment: worker writes them as they happen; the same reconnect reason again bumps repeats/last_at instead of
--comment: adding a row, so a reconnect loop is one line. New table: the previous release ignores it.
CREATE TABLE stream_events (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES stream_jobs(id) ON DELETE CASCADE,
    at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_at TIMESTAMP WITH TIME ZONE NOT NULL,
    kind VARCHAR(30) NOT NULL,
    position INT,
    name VARCHAR(255),
    message TEXT,
    repeats INT NOT NULL DEFAULT 1
);
CREATE INDEX idx_stream_events_job ON stream_events(job_id, id DESC);
