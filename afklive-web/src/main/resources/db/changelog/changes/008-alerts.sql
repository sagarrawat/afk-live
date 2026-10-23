--liquibase formatted sql

--changeset afklive:008-alerts
--comment: NT-1, NT-2, SC-5: alerts shown in the app and emailed. An incident is one outage of one run
--comment: (or one destination of it): opened when it has lasted long enough to matter, closed on recovery.
CREATE TABLE notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(40) NOT NULL,
    level VARCHAR(10) NOT NULL,
    stream_id BIGINT REFERENCES streams(id) ON DELETE SET NULL,
    title VARCHAR(200) NOT NULL,
    body TEXT,
    link VARCHAR(300),
    dedupe_key VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    read_at TIMESTAMP WITH TIME ZONE,
    emailed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_notifications_user ON notifications(user_id, created_at DESC);
CREATE INDEX idx_notifications_dedupe ON notifications(user_id, dedupe_key, created_at DESC);

CREATE TABLE stream_incidents (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES stream_jobs(id) ON DELETE CASCADE,
    kind VARCHAR(30) NOT NULL,
    target VARCHAR(100) NOT NULL DEFAULT '',
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE
);
-- At most one open incident per run, kind and target: two web instances can't both open (and alert) it.
CREATE UNIQUE INDEX idx_stream_incidents_open ON stream_incidents(job_id, kind, target) WHERE resolved_at IS NULL;

ALTER TABLE users ADD COLUMN email_alerts BOOLEAN NOT NULL DEFAULT TRUE;
