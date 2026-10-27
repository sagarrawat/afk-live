--liquibase formatted sql

--changeset afklive:170-plan-traffic-allowance
--comment: FO-5 (fair use): each plan's monthly streaming data allowance in GB (GiB, as transfer is billed). NULL = no
--comment: allowance, which is every plan until an admin sets one, so nothing changes on deploy. Expand only: nullable,
--comment: the previous release neither reads nor writes it (its plan saves list their own columns, so they keep it).
ALTER TABLE plan_configs ADD COLUMN monthly_traffic_gb INTEGER;

--changeset afklive:171-traffic-grants
--comment: FO-5: extra streaming data an admin grants one account for one calendar month (UTC), with who and why
--comment: (the action is also in admin_actions). New table only: the previous release ignores it.
CREATE TABLE traffic_grants (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    month VARCHAR(7) NOT NULL,
    extra_gb INTEGER NOT NULL,
    admin_email VARCHAR(255),
    reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_traffic_grants_user_month ON traffic_grants(user_id, month);

--changeset afklive:172-traffic-notices
--comment: FO-5: a creator is told once per month at 80%, 100% and 110% of their allowance. The (user, month, step) key
--comment: is the claim: with several web instances only the one whose insert lands sends it. A 100% row also marks an
--comment: account whose extra destinations were paused this month, so the sweep knows whom to restore after an upgrade
--comment: or a grant. New table only: the previous release ignores it.
CREATE TABLE traffic_notices (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    month VARCHAR(7) NOT NULL,
    step INTEGER NOT NULL,
    gb_sent DOUBLE PRECISION NOT NULL,
    allowance_gb DOUBLE PRECISION NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, month, step)
);
