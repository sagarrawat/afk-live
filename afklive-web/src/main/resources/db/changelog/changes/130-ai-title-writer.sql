--liquibase formatted sql

--changeset afklive:130-ai-title-writer
--comment: AI-1 / GR-10: the title writer. New tables and one nullable column only: the previous release ignores them.
--comment: ai_usage: drafts written per account per day (in the account's time zone). The fair-use limit is enforced by a
--comment: conditional upsert (count < limit), so several web instances can never let an account past it.
--comment: ai_drafts: the last draft per (stream, language, inputs) for 24 hours; asking again with the same inputs reads it
--comment: and costs nothing. users.ai_language: the language the creator last chose (en, hi, ta, te, bn, mr).
CREATE TABLE ai_usage (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day DATE NOT NULL,
    generations INTEGER NOT NULL,
    PRIMARY KEY (user_id, day)
);

CREATE TABLE ai_drafts (
    stream_id BIGINT NOT NULL REFERENCES streams(id) ON DELETE CASCADE,
    language VARCHAR(8) NOT NULL,
    inputs_hash VARCHAR(64) NOT NULL,
    draft TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (stream_id, language, inputs_hash)
);

ALTER TABLE users ADD COLUMN ai_language VARCHAR(8);
