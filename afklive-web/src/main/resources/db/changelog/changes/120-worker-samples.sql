--liquibase formatted sql

--changeset afklive:120-worker-samples
--comment: Admin console (AD-2): each worker's CPU and memory once a minute, so a single reading can be read against
--comment: its last 24 hours. Written web-side by WorkerSampler from stream_workers (workers are unchanged); the
--comment: (worker_id, at) key makes each minute a claim, so several web instances write it once. Pruned after 7 days.
--comment: New table only: the previous release ignores it.
CREATE TABLE worker_samples (
    worker_id VARCHAR(100) NOT NULL,
    at TIMESTAMP WITH TIME ZONE NOT NULL,
    cpu_percent DOUBLE PRECISION,
    memory_used_bytes BIGINT,
    memory_total_bytes BIGINT,
    streams INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (worker_id, at)
);
CREATE INDEX idx_worker_samples_at ON worker_samples(at);
