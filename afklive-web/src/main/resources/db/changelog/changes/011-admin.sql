--liquibase formatted sql

--changeset afklive:011-admin
--comment: AD-1 / AD-3: an audit trail for admin actions, and moving a stream off a worker.
CREATE TABLE admin_actions (
    id BIGSERIAL PRIMARY KEY,
    admin_email VARCHAR(255) NOT NULL,
    action VARCHAR(40) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    detail TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_actions_target ON admin_actions(target_type, target_id, created_at DESC);
CREATE INDEX idx_admin_actions_created ON admin_actions(created_at DESC);

ALTER TABLE stream_jobs ADD COLUMN avoid_worker VARCHAR(255);
ALTER TABLE stream_jobs ADD COLUMN move_requested_at TIMESTAMP WITH TIME ZONE;
