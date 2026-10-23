--liquibase formatted sql

--changeset afklive:020-account-security
--comment: ON-6: two-step sign-in with an authenticator app (TOTP) and one-time recovery codes.
--comment: ON-7: account deletion with a 7-day grace period.
-- TOTP secrets are encrypted at rest (SecretBox); recovery codes are stored only as SHA-256 hashes.
ALTER TABLE users ADD COLUMN two_factor_secret TEXT;
ALTER TABLE users ADD COLUMN two_factor_pending_secret TEXT;
ALTER TABLE users ADD COLUMN two_factor_enabled_at TIMESTAMP WITH TIME ZONE;
-- The last 30-second step a code was accepted for, so a code can't be used twice.
ALTER TABLE users ADD COLUMN two_factor_last_step BIGINT;
-- Wrong codes in a row, and until when code entry is locked after too many.
ALTER TABLE users ADD COLUMN two_factor_failures INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN two_factor_locked_until TIMESTAMP WITH TIME ZONE;

CREATE TABLE two_factor_recovery_codes (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash VARCHAR(64) NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (user_id, code_hash)
);

-- Deletion: the account closes at once and its data is removed once deletion_scheduled_for has passed.
ALTER TABLE users ADD COLUMN deletion_requested_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN deletion_scheduled_for TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_users_deletion_due ON users(deletion_scheduled_for) WHERE deletion_scheduled_for IS NOT NULL;
