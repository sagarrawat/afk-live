--liquibase formatted sql

--changeset afklive:180-payment-audits-offer-index
--comment: BI-9 offer results and the edit lock read payments by launch offer. Partial index: only payments that used an
--comment: offer are in it, so it stays tiny. Expand only: the previous release doesn't notice it.
CREATE INDEX IF NOT EXISTS idx_payment_audits_offer ON payment_audits(offer_id) WHERE offer_id IS NOT NULL;
