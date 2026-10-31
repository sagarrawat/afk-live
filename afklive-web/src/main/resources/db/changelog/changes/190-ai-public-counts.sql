--liquibase formatted sql

--changeset afklive:190-ai-public-counts
--comment: MK-7 / GR-10: the free public title writer's limits, counted in the database so several web instances share
--comment: them. One row per counter: per visitor per hour and per day (the visitor is a SHA-256 of their IP address and
--comment: the day, never the address itself) and all visitors per day. Each request is one conditional upsert
--comment: (count < limit). Rows are deleted once expires_at passes (two days at most). New table only: the previous
--comment: release ignores it.
CREATE TABLE ai_public_counts (
    counter VARCHAR(120) PRIMARY KEY,
    count INTEGER NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_ai_public_counts_expires ON ai_public_counts(expires_at);
