--liquibase formatted sql

--changeset afklive:160-account-security
--comment: ON-6: change email with a verification link, and "sign out other devices". sessions_version is part of
--comment: every remember-me cookie's signature (when above 0), so raising it ends "keep me signed in" on every
--comment: device; the current one gets a fresh cookie. Expand only: the previous release ignores these columns.
ALTER TABLE users ADD COLUMN sessions_version INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN pending_email VARCHAR(255);
ALTER TABLE users ADD COLUMN email_change_token_hash VARCHAR(64);
ALTER TABLE users ADD COLUMN email_change_expires_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_users_email_change_token ON users(email_change_token_hash) WHERE email_change_token_hash IS NOT NULL;
