--liquibase formatted sql

--changeset afklive:135-media-deletions
--comment: ML-6: every file a creator deletes from the library is recorded, so support can see later what was deleted,
--comment: when and from which streams. The file itself is gone; this keeps only what it was. Expand only.
CREATE TABLE media_deletions (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    media_id       BIGINT       NOT NULL,
    title          VARCHAR(255),
    media_type     VARCHAR(16),
    file_size      BIGINT       NOT NULL DEFAULT 0,
    storage_key    VARCHAR(1024),
    thumbnail_key  VARCHAR(1024),
    removed_from   TEXT,
    created_at     TIMESTAMP    NOT NULL,
    deleted_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_media_deletions_user ON media_deletions(user_id, deleted_at DESC);
