--liquibase formatted sql

--changeset afklive:035-email-log
--comment: OPS-1: every email we try to send, so support can see whether a verification, reset or alert
--comment: went out. Never the body or the address: the kind, the recipient's account (when there is one),
--comment: and whether the mail service took it.
CREATE TABLE email_log (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    kind VARCHAR(30) NOT NULL,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    ok BOOLEAN NOT NULL,
    error VARCHAR(300)
);
CREATE INDEX idx_email_log_user ON email_log(user_id, created_at DESC);
CREATE INDEX idx_email_log_created ON email_log(created_at DESC);

--changeset afklive:035-stream-job-logs
--comment: AD-1 / AD-3: the last FFmpeg lines of each run (encoder and every destination), written by the
--comment: worker running it with stream keys removed. One row per run, replaced as a whole: bounded by design.
CREATE TABLE stream_job_logs (
    job_id BIGINT PRIMARY KEY REFERENCES stream_jobs(id) ON DELETE CASCADE,
    lines TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_stream_job_logs_updated ON stream_job_logs(updated_at);
