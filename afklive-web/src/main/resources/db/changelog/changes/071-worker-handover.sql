--liquibase formatted sql

--changeset afklive:071-worker-handover
--comment: OPS-3: deploying a new worker version hands each live stream to the new process without a break.
--comment: youtube_ingest is the YouTube ingest the owner publishes to (PRIMARY or BACKUP; NULL = PRIMARY): a
--comment: copy being handed over uses the other one, so both are connected for a few seconds and YouTube
--comment: fails over without ending the broadcast. handover_* is one planned handover: offered by the owner
--comment: (handover_from, with its playlist position), claimed by another worker (handover_to) that keeps a
--comment: fenced beat while its copy starts, then ready; the owner completes it with one conditional update.
--comment: All NULL when no handover is in progress. Expand only: the previous release ignores these columns.
ALTER TABLE stream_jobs ADD COLUMN youtube_ingest VARCHAR(10);
ALTER TABLE stream_jobs ADD COLUMN handover_from VARCHAR(255);
ALTER TABLE stream_jobs ADD COLUMN handover_to VARCHAR(255);
ALTER TABLE stream_jobs ADD COLUMN handover_offered_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE stream_jobs ADD COLUMN handover_position TEXT;
ALTER TABLE stream_jobs ADD COLUMN handover_claimed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE stream_jobs ADD COLUMN handover_beat_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE stream_jobs ADD COLUMN handover_ready_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_stream_jobs_handover_offers ON stream_jobs(handover_from) WHERE is_live = true AND handover_from IS NOT NULL;
