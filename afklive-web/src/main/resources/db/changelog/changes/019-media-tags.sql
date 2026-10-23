--liquibase formatted sql

--changeset afklive:019-media-tags
--comment: ML-8: a creator's own tags for library files (by show or mood); a file can have many tags.
CREATE TABLE media_tags (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(40) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_media_tags_user_name ON media_tags(user_id, lower(name));

CREATE TABLE media_asset_tags (
    media_asset_id BIGINT NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES media_tags(id) ON DELETE CASCADE,
    PRIMARY KEY (media_asset_id, tag_id)
);
CREATE INDEX idx_media_asset_tags_tag ON media_asset_tags(tag_id);
