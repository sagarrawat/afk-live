--liquibase formatted sql

--changeset afklive:185-ai-spend
--comment: AI-1 / GR-10 FinOps: tokens and cost of every AI call, per UTC month, provider and model, counted with one
--comment: upsert per call so several web instances add up correctly. AI stops for the rest of the month once the sum
--comment: reaches APP_AI_MONTHLY_BUDGET_USD. ai_budget_alerts: admins are told once at 80% and once at 100% per month;
--comment: the (month, threshold) key is the claim. New tables only: the previous release ignores them.
CREATE TABLE ai_spend (
    month VARCHAR(7) NOT NULL,
    provider VARCHAR(16) NOT NULL,
    model VARCHAR(80) NOT NULL,
    calls BIGINT NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    cost_usd NUMERIC(14, 6) NOT NULL DEFAULT 0,
    PRIMARY KEY (month, provider, model)
);

CREATE TABLE ai_budget_alerts (
    month VARCHAR(7) NOT NULL,
    threshold INTEGER NOT NULL,
    spent_usd NUMERIC(14, 6) NOT NULL,
    budget_usd NUMERIC(14, 6) NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (month, threshold)
);
