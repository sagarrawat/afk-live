--liquibase formatted sql

--changeset afklive:007-phone-ingest
--comment: Phone streaming (MS-1, MS-2): each phone stream has a private ingest key (stored encrypted,
--comment: looked up by its hash), how long BRB may stay on before the stream ends, and the SRT buffer.
ALTER TABLE streams ADD COLUMN ingest_key_hash VARCHAR(64);
ALTER TABLE streams ADD COLUMN ingest_key TEXT;
ALTER TABLE streams ADD COLUMN ingest_last_publish_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE streams ADD COLUMN end_on_input_loss BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE streams ADD COLUMN input_grace_seconds INTEGER NOT NULL DEFAULT 120;
ALTER TABLE streams ADD COLUMN srt_latency_ms INTEGER NOT NULL DEFAULT 1000;
CREATE UNIQUE INDEX idx_streams_ingest_key_hash ON streams(ingest_key_hash);

-- MS-6 "Test" for a destination: requested by the web app, run on a stream worker (which has the
-- egress firewall), result read back by the web app.
CREATE TABLE destination_tests (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    destination_id BIGINT REFERENCES stream_destinations(id) ON DELETE CASCADE,
    url TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    message TEXT,
    worker_id VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_destination_tests_pending ON destination_tests(status) WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX idx_destination_tests_user ON destination_tests(user_id, created_at DESC);
