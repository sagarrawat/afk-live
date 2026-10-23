--liquibase formatted sql

--changeset afklive:009-billing
--comment: BI-1..BI-8: plans priced per concurrent stream (monthly/yearly, INR and USD), free monthly
--comment: hours, subscriptions with proration and end-of-period changes, GST invoices, usage metering.
ALTER TABLE plan_configs ADD COLUMN max_fps INTEGER NOT NULL DEFAULT 30;
ALTER TABLE plan_configs ADD COLUMN monthly_hours INTEGER NOT NULL DEFAULT 0;
ALTER TABLE plan_configs ADD COLUMN watermark BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE plan_configs ADD COLUMN max_destinations INTEGER NOT NULL DEFAULT 1;
ALTER TABLE plan_configs ADD COLUMN price_inr_month BIGINT NOT NULL DEFAULT 0;
ALTER TABLE plan_configs ADD COLUMN price_inr_year BIGINT NOT NULL DEFAULT 0;
ALTER TABLE plan_configs ADD COLUMN price_usd_month BIGINT NOT NULL DEFAULT 0;
ALTER TABLE plan_configs ADD COLUMN price_usd_year BIGINT NOT NULL DEFAULT 0;
-- Rows created before these columns existed get their plan's defaults again at startup.
ALTER TABLE plan_configs ADD COLUMN defaults_version INTEGER NOT NULL DEFAULT 0;

CREATE TABLE subscriptions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    plan_type VARCHAR(50) NOT NULL,
    billing_cycle VARCHAR(10) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(20) NOT NULL,
    current_period_start TIMESTAMP WITH TIME ZONE NOT NULL,
    current_period_end TIMESTAMP WITH TIME ZONE NOT NULL,
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
    pending_plan VARCHAR(50),
    pending_cycle VARCHAR(10),
    renewal_reminded_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_subscriptions_period_end ON subscriptions(current_period_end);

CREATE TABLE billing_profiles (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    legal_name VARCHAR(200),
    address_line VARCHAR(400),
    city VARCHAR(100),
    state_code VARCHAR(2),
    postal_code VARCHAR(12),
    country VARCHAR(2) NOT NULL DEFAULT 'IN',
    gstin VARCHAR(15)
);

-- Invoice numbers are sequential per Indian financial year (GST rule): one counter row per year.
CREATE TABLE invoice_counters (
    financial_year VARCHAR(9) PRIMARY KEY,
    last_number INTEGER NOT NULL
);

CREATE TABLE invoices (
    id BIGSERIAL PRIMARY KEY,
    number VARCHAR(40) NOT NULL UNIQUE,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    payment_order_id VARCHAR(255) UNIQUE,
    description VARCHAR(300) NOT NULL,
    period_start TIMESTAMP WITH TIME ZONE,
    period_end TIMESTAMP WITH TIME ZONE,
    currency VARCHAR(3) NOT NULL,
    taxable_amount BIGINT NOT NULL,
    cgst BIGINT NOT NULL DEFAULT 0,
    sgst BIGINT NOT NULL DEFAULT 0,
    igst BIGINT NOT NULL DEFAULT 0,
    total BIGINT NOT NULL,
    customer_name VARCHAR(200),
    customer_address TEXT,
    customer_state VARCHAR(2),
    customer_gstin VARCHAR(15),
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_invoices_user ON invoices(user_id, issued_at DESC);

CREATE TABLE usage_months (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    month VARCHAR(7) NOT NULL,
    stream_seconds BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, month)
);

ALTER TABLE payment_audits ADD COLUMN kind VARCHAR(20);
ALTER TABLE payment_audits ADD COLUMN billing_cycle VARCHAR(10);
ALTER TABLE payment_audits ADD COLUMN currency VARCHAR(3);
ALTER TABLE payment_audits ADD COLUMN credit BIGINT NOT NULL DEFAULT 0;
