--liquibase formatted sql

--changeset afklive:003-stream-job-stop-and-reason
--comment: Scheduled stop time for a running stream, and why a stream ended.
ALTER TABLE stream_jobs ADD COLUMN stop_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE stream_jobs ADD COLUMN end_reason VARCHAR(255);
CREATE INDEX idx_stream_jobs_live_stop_at ON stream_jobs(stop_at) WHERE is_live = TRUE;
