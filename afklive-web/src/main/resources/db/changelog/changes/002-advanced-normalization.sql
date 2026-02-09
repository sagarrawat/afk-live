--liquibase formatted sql

--changeset jules:002-advanced-normalization
--comment: Separate Media, Tags, and Settings

-- 1. User Settings Table (1:1 with Users)
CREATE TABLE user_settings (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    auto_reply_enabled BOOLEAN DEFAULT FALSE,
    delete_negative_comments BOOLEAN DEFAULT FALSE,
    auto_reply_unreplied_enabled BOOLEAN DEFAULT FALSE,
    auto_reply_unreplied_message TEXT
);

-- Migrate existing settings from users table
INSERT INTO user_settings (user_id, auto_reply_enabled, delete_negative_comments, auto_reply_unreplied_enabled, auto_reply_unreplied_message)
SELECT id, auto_reply_enabled, delete_negative_comments, auto_reply_unreplied_enabled, auto_reply_unreplied_message
FROM users;

-- Remove settings columns from users
ALTER TABLE users DROP COLUMN auto_reply_enabled;
ALTER TABLE users DROP COLUMN delete_negative_comments;
ALTER TABLE users DROP COLUMN auto_reply_unreplied_enabled;
ALTER TABLE users DROP COLUMN auto_reply_unreplied_message;


-- 2. Media Assets Table
CREATE TABLE media_assets (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(255),
    s3_key VARCHAR(255) NOT NULL,
    file_size BIGINT DEFAULT 0,
    content_type VARCHAR(50), -- VIDEO, IMAGE, AUDIO
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_media_assets_user ON media_assets(user_id);
CREATE INDEX idx_media_assets_s3key ON media_assets(s3_key);


-- 3. Migrate ScheduledVideo Data to MediaAssets
-- We create asset records for existing videos
INSERT INTO media_assets (user_id, title, s3_key, file_size, content_type, created_at)
SELECT user_id, title, s3_key, file_size, 'VIDEO', COALESCE(scheduled_time, CURRENT_TIMESTAMP)
FROM scheduled_videos
WHERE s3_key IS NOT NULL;

-- 4. Update ScheduledVideo Schema
ALTER TABLE scheduled_videos ADD COLUMN media_asset_id BIGINT REFERENCES media_assets(id);
ALTER TABLE scheduled_videos ADD COLUMN thumbnail_asset_id BIGINT REFERENCES media_assets(id);

-- Link migrated assets back to videos
UPDATE scheduled_videos sv
SET media_asset_id = ma.id
FROM media_assets ma
WHERE sv.s3_key = ma.s3_key AND sv.user_id = ma.user_id;

-- Now drop old columns
ALTER TABLE scheduled_videos DROP COLUMN s3_key;
ALTER TABLE scheduled_videos DROP COLUMN file_size;
ALTER TABLE scheduled_videos DROP COLUMN thumbnail_s3_key;


-- 5. Tags Normalization
CREATE TABLE tags (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) UNIQUE NOT NULL
);

CREATE TABLE video_tags (
    video_id BIGINT NOT NULL REFERENCES scheduled_videos(id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (video_id, tag_id)
);

-- Note: Splitting existing CSV tags in SQL is complex and DB-specific.
-- For this refactor, we accept that existing tags might need manual migration or remain as legacy text if we don't drop the column yet.
-- To keep it clean, we will DROP the tags column. Users re-tagging is acceptable for a "Massive Refactor".
ALTER TABLE scheduled_videos DROP COLUMN tags;


-- 6. Update ScheduledStream Schema
ALTER TABLE scheduled_streams ADD COLUMN media_asset_id BIGINT REFERENCES media_assets(id);

-- Migrate video_key to media_asset (if match found, else null)
UPDATE scheduled_streams ss
SET media_asset_id = ma.id
FROM media_assets ma
WHERE ss.video_key = ma.s3_key OR ss.video_key = ma.title; -- Title fallback

ALTER TABLE scheduled_streams DROP COLUMN video_key;


-- 7. Normalize Stream Destinations for Scheduled Streams
CREATE TABLE scheduled_stream_destinations (
    scheduled_stream_id BIGINT NOT NULL REFERENCES scheduled_streams(id) ON DELETE CASCADE,
    stream_destination_id BIGINT NOT NULL REFERENCES stream_destinations(id) ON DELETE CASCADE,
    PRIMARY KEY (scheduled_stream_id, stream_destination_id)
);

-- Drop the old raw string table
DROP TABLE scheduled_stream_stream_keys;
