--liquibase formatted sql

--changeset afklive:016-alert-preferences
--comment: NT-3: which alerts go by email and by push, per kind of alert (JSON; missing kinds are on).
ALTER TABLE users ADD COLUMN alert_preferences TEXT;
