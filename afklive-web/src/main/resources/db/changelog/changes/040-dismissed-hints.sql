--liquibase formatted sql

--changeset afklive:040-dismissed-hints
--comment: GL-6: tips a creator has dismissed ("What you'll see on YouTube"), remembered per account across
--comment: devices. Comma-separated ids from MeController.HINTS; null: none dismissed.
ALTER TABLE users ADD COLUMN dismissed_hints VARCHAR(500);
