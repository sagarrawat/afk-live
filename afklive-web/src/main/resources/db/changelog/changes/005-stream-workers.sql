--liquibase formatted sql

--changeset afklive:005-stream-workers
--comment: Streams run on worker processes. The web app records what should run (spec, desired state);
--comment: a worker claims the job, reports health through a heartbeat, and records each destination's state.
ALTER TABLE stream_jobs ADD COLUMN status VARCHAR(30);
ALTER TABLE stream_jobs ADD COLUMN desired_state VARCHAR(10);
ALTER TABLE stream_jobs ADD COLUMN spec TEXT;
ALTER TABLE stream_jobs ADD COLUMN worker_id VARCHAR(100);
ALTER TABLE stream_jobs ADD COLUMN heartbeat_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE stream_jobs ADD COLUMN restarts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE stream_jobs ADD COLUMN bitrate_kbps DOUBLE PRECISION;
ALTER TABLE stream_jobs ADD COLUMN fps DOUBLE PRECISION;
ALTER TABLE stream_jobs ADD COLUMN speed DOUBLE PRECISION;
ALTER TABLE stream_jobs ADD COLUMN dropped_frames BIGINT;
ALTER TABLE stream_jobs ADD COLUMN last_error TEXT;

-- Existing rows predate workers: anything still live is stale.
UPDATE stream_jobs SET status = 'ENDED', desired_state = 'STOP',
       is_live = FALSE, end_reason = COALESCE(end_reason, 'Migrated'),
       end_time = COALESCE(end_time, last_billing_time, start_time) WHERE status IS NULL;

CREATE INDEX idx_stream_jobs_worker ON stream_jobs(worker_id) WHERE is_live = TRUE;

CREATE TABLE stream_job_destinations (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES stream_jobs(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    name VARCHAR(255),
    status VARCHAR(30),
    restarts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    updated_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_stream_job_destinations_job ON stream_job_destinations(job_id);
