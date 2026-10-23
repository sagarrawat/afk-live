--liquibase formatted sql

--changeset afklive:013-push
--comment: NT-1 / MB-2: browsers and installed apps that receive alerts as push notifications.
CREATE TABLE push_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint TEXT NOT NULL,
    p256dh VARCHAR(200) NOT NULL,
    auth VARCHAR(100) NOT NULL,
    user_agent VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_sent_at TIMESTAMP WITH TIME ZONE
);
CREATE UNIQUE INDEX ux_push_subscriptions_endpoint ON push_subscriptions(endpoint);
CREATE INDEX ix_push_subscriptions_user ON push_subscriptions(user_id);
