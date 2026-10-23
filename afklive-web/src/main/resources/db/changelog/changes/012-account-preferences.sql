--liquibase formatted sql

--changeset afklive:012-account-preferences
--comment: ON-2 / ON-3: time zone and currency per account, and whether the setup wizard was finished or skipped.
ALTER TABLE users ADD COLUMN time_zone VARCHAR(64);
ALTER TABLE users ADD COLUMN currency VARCHAR(3);
ALTER TABLE users ADD COLUMN onboarded_at TIMESTAMP WITH TIME ZONE;
