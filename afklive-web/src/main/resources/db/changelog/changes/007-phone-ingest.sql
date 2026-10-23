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
