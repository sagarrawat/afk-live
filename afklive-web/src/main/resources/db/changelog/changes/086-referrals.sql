--liquibase formatted sql

--changeset afklive:086-referrals
--comment: GR-18 acquisition loop. Expand only: the previous release never reads or writes these.
--comment: users.referral_code: the creator's invite code (made on first use). users.credit_line: opted in to the
--comment: "Streamed 24/7 with AFK Live" line in descriptions AFK Live writes (off by default, never on screen).
--comment: referrals: who invited whom. A referral is JOINED at sign-up; the INR credit is paid only after the invited
--comment: creator's first confirmed live (confirmed_live_at / credited_at), which waits for RE-8.
ALTER TABLE users ADD COLUMN referral_code VARCHAR(16);
CREATE UNIQUE INDEX uq_users_referral_code ON users(referral_code);
ALTER TABLE users ADD COLUMN credit_line BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE referrals (
    id BIGSERIAL PRIMARY KEY,
    referrer_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    referred_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'JOINED',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    confirmed_live_at TIMESTAMP WITH TIME ZONE,
    credited_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_referrals_referred UNIQUE (referred_id),
    CONSTRAINT ck_referrals_not_self CHECK (referrer_id <> referred_id)
);
CREATE INDEX idx_referrals_referrer ON referrals(referrer_id);
