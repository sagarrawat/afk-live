--liquibase formatted sql

--changeset afklive:025-truth-of-live
--comment: RE-8 / RE-1 / RE-3: what happened to each destination of a run (reaching the platform, lost it and why,
--comment: back again), written by the worker as it happens. A failure that repeats with the same reason bumps
--comment: repeats/last_at instead of adding a row, so a reconnect loop is one line on the timeline.
CREATE TABLE stream_events (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES stream_jobs(id) ON DELETE CASCADE,
    at TIMESTAMP WITH TIME ZONE NOT NULL,
    kind VARCHAR(30) NOT NULL,
    position INT,
    name VARCHAR(200),
    message TEXT,
    repeats INT NOT NULL DEFAULT 1,
    last_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_stream_events_job ON stream_events(job_id, id DESC);

--comment: FO-3: bytes each destination actually sent (all reconnects of the run), reported with the heartbeat.
ALTER TABLE stream_job_destinations ADD COLUMN bytes_sent BIGINT NOT NULL DEFAULT 0;

--comment: GL-1: Go Live looks up each destination's latest test.
CREATE INDEX idx_destination_tests_destination ON destination_tests(destination_id, id DESC);
