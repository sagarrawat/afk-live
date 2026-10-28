--liquibase formatted sql

--changeset afklive:175-launch-offers
--comment: BI-9 launch offers: a genuine, time-boxed discount for new subscribers, created and ended by an admin
--comment: (audited in admin_actions too). New table only: the previous release ignores it.
CREATE TABLE launch_offers (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    code VARCHAR(32) UNIQUE,
    automatic BOOLEAN NOT NULL,
    percent_off INTEGER NOT NULL CHECK (percent_off BETWEEN 1 AND 50),
    monthly_periods INTEGER NOT NULL CHECK (monthly_periods BETWEEN 1 AND 12),
    include_yearly BOOLEAN NOT NULL,
    plans VARCHAR(100) NOT NULL,
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE,
    created_by VARCHAR(255),
    created_reason TEXT,
    ended_by VARCHAR(255),
    ended_reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at),
    CHECK (automatic OR code IS NOT NULL)
);

--changeset afklive:176-offer-redemptions
--comment: BI-9: one offer per account, ever (the primary key is the claim, so two payments racing can't both
--comment: redeem). periods_used counts discounted payments; renewals go back to the list price when it reaches
--comment: periods_total. New table only: the previous release ignores it.
CREATE TABLE offer_redemptions (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    offer_id BIGINT NOT NULL REFERENCES launch_offers(id),
    plan_type VARCHAR(50) NOT NULL,
    billing_cycle VARCHAR(10) NOT NULL,
    percent_off INTEGER NOT NULL,
    periods_total INTEGER NOT NULL,
    periods_used INTEGER NOT NULL,
    first_order_id VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_offer_redemptions_offer ON offer_redemptions(offer_id);

--changeset afklive:177-payment-discounts
--comment: BI-9: what each payment and invoice was discounted by, and the price of the current period after any
--comment: discount (the upgrade credit is worked out from what was paid, not the list price). Expand only: nullable
--comment: or defaulted columns the previous release neither reads nor writes.
ALTER TABLE payment_audits ADD COLUMN offer_id BIGINT;
ALTER TABLE payment_audits ADD COLUMN list_amount BIGINT;
ALTER TABLE payment_audits ADD COLUMN discount_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE invoices ADD COLUMN list_amount BIGINT;
ALTER TABLE invoices ADD COLUMN discount_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE invoices ADD COLUMN discount_label VARCHAR(120);
ALTER TABLE subscriptions ADD COLUMN period_price BIGINT;

--changeset afklive:178-subscription-terms
--comment: BI-10 grandfathering: the plan limits a subscriber bought, recorded on the subscription. Limit checks use
--comment: the more generous of these and the plan's current limits, so a later plan_configs change never reduces
--comment: what a paying customer has. Expand only: nullable columns the previous release ignores.
ALTER TABLE subscriptions ADD COLUMN terms_plan VARCHAR(50);
ALTER TABLE subscriptions ADD COLUMN terms_streams INTEGER;
ALTER TABLE subscriptions ADD COLUMN terms_destinations INTEGER;
ALTER TABLE subscriptions ADD COLUMN terms_max_height INTEGER;
ALTER TABLE subscriptions ADD COLUMN terms_max_fps INTEGER;
ALTER TABLE subscriptions ADD COLUMN terms_storage_bytes BIGINT;
ALTER TABLE subscriptions ADD COLUMN terms_monthly_hours INTEGER;
ALTER TABLE subscriptions ADD COLUMN terms_traffic_gb INTEGER;
ALTER TABLE subscriptions ADD COLUMN terms_watermark BOOLEAN;
ALTER TABLE subscriptions ADD COLUMN terms_captured_at TIMESTAMP WITH TIME ZONE;

--changeset afklive:179-subscription-terms-backfill
--comment: BI-10: every subscription that isn't canceled keeps the limits in effect today. Only fills rows without a
--comment: snapshot; the previous release never reads these columns. A row the previous release creates during a
--comment: rolling deploy is snapshotted by the admin "apply limits for new sign-ups" step before any limit changes.
UPDATE subscriptions s SET
    terms_plan = s.plan_type,
    terms_streams = c.max_active_streams,
    terms_destinations = c.max_destinations,
    terms_max_height = c.max_resolution,
    terms_max_fps = c.max_fps,
    terms_storage_bytes = c.max_storage_bytes,
    terms_monthly_hours = c.monthly_hours,
    terms_traffic_gb = c.monthly_traffic_gb,
    terms_watermark = c.watermark,
    terms_captured_at = now()
FROM plan_configs c
WHERE c.plan_type = s.plan_type AND s.status <> 'CANCELED' AND s.terms_plan IS NULL;
