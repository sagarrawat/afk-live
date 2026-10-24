--liquibase formatted sql

--changeset afklive:065-one-live-run-per-stream
--comment: Production, 24 Oct 2026: a double tap on Go live left two live runs (jobs 19 and 20) of one stream,
--comment: both claimed. A stream has at most one live run, enforced by the database. Any duplicates still live
--comment: are ended first, keeping the oldest run: the newer one's worker sees its heartbeat fail and stops its
--comment: copy (two runs of one stream push to the same stream key, so the newer copy only does harm).
--comment: Expand-safe: the previous release keeps working; a duplicate insert from it fails instead of running twice.
UPDATE stream_jobs d SET is_live = false, desired_state = 'STOP', status = 'ENDED', end_time = now(),
       end_reason = 'Duplicate run of the same stream (ended by an update)'
WHERE d.is_live AND d.stream_id IS NOT NULL
  AND EXISTS (SELECT 1 FROM stream_jobs o WHERE o.stream_id = d.stream_id AND o.is_live AND o.id < d.id);
CREATE UNIQUE INDEX uq_stream_jobs_one_live_per_stream ON stream_jobs(stream_id) WHERE is_live AND stream_id IS NOT NULL;
