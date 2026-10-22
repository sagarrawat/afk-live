--liquibase formatted sql

-- Development-only seed data. Runs only when Liquibase contexts include "dev"
-- (the dev Spring profile sets this). Never runs in production.

--changeset afklive:002-dev-seed context:dev
--comment: Local developer account: dev@afklive.local / devpassword (ROLE_ADMIN)
INSERT INTO users (email, password, enabled, plan_type, full_name, used_storage_bytes, role)
VALUES ('dev@afklive.local', '$2a$10$cZmjaGXLFjbG9FLV/BDcG.ydHGXxdIc8fyAhjTjR8uT3f09kQIwg6', true, 'FREE', 'Dev Admin', 0, 'ROLE_ADMIN')
ON CONFLICT (email) DO NOTHING;

INSERT INTO user_settings (user_id, auto_reply_enabled, delete_negative_comments)
SELECT id, false, false FROM users WHERE email = 'dev@afklive.local'
ON CONFLICT (user_id) DO NOTHING;
