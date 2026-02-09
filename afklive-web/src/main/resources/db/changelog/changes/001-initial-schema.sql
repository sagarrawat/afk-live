--liquibase formatted sql

--changeset jules:001-initial-schema
--comment: Initial normalized schema (Merged)

-- Drop existing tables if they exist (clean slate)
DROP TABLE IF EXISTS oauth2_authorized_client CASCADE;
DROP TABLE IF EXISTS scheduled_stream_stream_keys CASCADE;
DROP TABLE IF EXISTS scheduled_stream_destinations CASCADE;
DROP TABLE IF EXISTS scheduled_streams CASCADE;
DROP TABLE IF EXISTS video_tags CASCADE;
DROP TABLE IF EXISTS tags CASCADE;
DROP TABLE IF EXISTS scheduled_videos CASCADE;
DROP TABLE IF EXISTS social_channels CASCADE;
DROP TABLE IF EXISTS stream_jobs CASCADE;
DROP TABLE IF EXISTS stream_destinations CASCADE;
DROP TABLE IF EXISTS engagement_activity CASCADE;
DROP TABLE IF EXISTS payment_audits CASCADE;
DROP TABLE IF EXISTS quota_audit CASCADE;
DROP TABLE IF EXISTS processed_comments CASCADE;
DROP TABLE IF EXISTS support_tickets CASCADE;
DROP TABLE IF EXISTS user_settings CASCADE;
DROP TABLE IF EXISTS media_assets CASCADE;
DROP TABLE IF EXISTS users CASCADE;
DROP TABLE IF EXISTS app_settings CASCADE;
DROP TABLE IF EXISTS plan_configs CASCADE;
DROP TABLE IF EXISTS etag_store CASCADE;

-- Create OAuth2 Client Table (Standard Spring Security)
CREATE TABLE oauth2_authorized_client (
  client_registration_id varchar(100) NOT NULL,
  principal_name varchar(200) NOT NULL,
  access_token_type varchar(100) NOT NULL,
  access_token_value bytea NOT NULL,
  access_token_issued_at timestamp NOT NULL,
  access_token_expires_at timestamp NOT NULL,
  access_token_scopes varchar(1000) DEFAULT NULL,
  refresh_token_value bytea DEFAULT NULL,
  refresh_token_issued_at timestamp DEFAULT NULL,
  created_at timestamp DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (client_registration_id, principal_name)
);

-- Users Table
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255),
    full_name VARCHAR(255),
    picture_url TEXT,
    role VARCHAR(50) DEFAULT 'ROLE_USER',
    enabled BOOLEAN DEFAULT TRUE,

    -- Plan & Billing
    plan_type VARCHAR(50) DEFAULT 'FREE',
    plan_expiration TIMESTAMP,
    used_storage_bytes BIGINT DEFAULT 0,
    unpaid_balance DOUBLE PRECISION DEFAULT 0.0,
    credit_limit DOUBLE PRECISION DEFAULT 50.0,

    -- Verification & Security
    verification_token VARCHAR(255),
    last_verification_sent_at TIMESTAMP,
    reset_token VARCHAR(255)
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_verification_token ON users(verification_token);
CREATE INDEX idx_users_reset_token ON users(reset_token);

-- Full Text Search Index for Users
CREATE INDEX idx_user_search_fts ON users USING GIN (to_tsvector('english', email || ' ' || coalesce(full_name, '')));


-- User Settings (1:1)
CREATE TABLE user_settings (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    auto_reply_enabled BOOLEAN DEFAULT FALSE,
    delete_negative_comments BOOLEAN DEFAULT FALSE,
    auto_reply_unreplied_enabled BOOLEAN DEFAULT FALSE,
    auto_reply_unreplied_message TEXT
);


-- Media Assets
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


-- Social Channels
CREATE TABLE social_channels (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(255),
    platform VARCHAR(50),
    profile_url VARCHAR(255),
    credential_id VARCHAR(255)
);

CREATE INDEX idx_social_channels_user_id ON social_channels(user_id);


-- Stream Destinations
CREATE TABLE stream_destinations (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(255),
    stream_key VARCHAR(1024),
    type VARCHAR(50),
    selected BOOLEAN DEFAULT TRUE
);

CREATE INDEX idx_stream_destinations_user_id ON stream_destinations(user_id);


-- Tags
CREATE TABLE tags (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) UNIQUE NOT NULL
);


-- Scheduled Videos
CREATE TABLE scheduled_videos (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    social_channel_id BIGINT REFERENCES social_channels(id) ON DELETE SET NULL,
    media_asset_id BIGINT REFERENCES media_assets(id),
    thumbnail_asset_id BIGINT REFERENCES media_assets(id),

    title VARCHAR(255),
    description TEXT,
    privacy_status VARCHAR(50),
    category_id VARCHAR(50),
    scheduled_time TIMESTAMP WITH TIME ZONE,

    youtube_video_id VARCHAR(255),

    first_comment TEXT,
    status VARCHAR(50) DEFAULT 'PENDING',
    error_message TEXT,
    optimization_status VARCHAR(50) DEFAULT 'NOT_OPTIMIZED'
);

CREATE INDEX idx_scheduled_videos_user_id ON scheduled_videos(user_id);
CREATE INDEX idx_scheduled_videos_status ON scheduled_videos(status);
-- FTS for Video Title
CREATE INDEX idx_schedvideo_title_fts ON scheduled_videos USING GIN (to_tsvector('english', coalesce(title, '')));


-- Video Tags (M:N)
CREATE TABLE video_tags (
    video_id BIGINT NOT NULL REFERENCES scheduled_videos(id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (video_id, tag_id)
);


-- Scheduled Streams
CREATE TABLE scheduled_streams (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_asset_id BIGINT REFERENCES media_assets(id),

    scheduled_time TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(50),
    music_name VARCHAR(255),
    music_volume VARCHAR(50),
    loop_count INTEGER DEFAULT -1,
    stream_mode VARCHAR(50) DEFAULT 'original',
    mute_video_audio BOOLEAN DEFAULT TRUE,
    error_message TEXT
);

CREATE INDEX idx_scheduled_streams_user_id ON scheduled_streams(user_id);


-- Scheduled Stream Destinations (M:N)
CREATE TABLE scheduled_stream_destinations (
    scheduled_stream_id BIGINT NOT NULL REFERENCES scheduled_streams(id) ON DELETE CASCADE,
    stream_destination_id BIGINT NOT NULL REFERENCES stream_destinations(id) ON DELETE CASCADE,
    PRIMARY KEY (scheduled_stream_id, stream_destination_id)
);


-- Stream Jobs (Active/History)
CREATE TABLE stream_jobs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    stream_key VARCHAR(1024),
    file_name VARCHAR(255),
    is_live BOOLEAN DEFAULT FALSE,
    pid BIGINT,
    music_name VARCHAR(255),
    music_volume VARCHAR(50),
    destination_name VARCHAR(255),

    title TEXT,
    description TEXT,
    privacy VARCHAR(50),

    start_time TIMESTAMP WITH TIME ZONE,
    end_time TIMESTAMP WITH TIME ZONE,

    cost DOUBLE PRECISION,
    accumulated_cost DOUBLE PRECISION DEFAULT 0.0,
    last_billing_time TIMESTAMP WITH TIME ZONE,

    auto_reply_enabled BOOLEAN DEFAULT FALSE,
    last_page_token TEXT,
    live_chat_id VARCHAR(255)
);

CREATE INDEX idx_stream_jobs_user_id ON stream_jobs(user_id);
CREATE INDEX idx_stream_jobs_is_live ON stream_jobs(is_live);
CREATE INDEX idx_stream_jobs_user_time ON stream_jobs(user_id, start_time DESC);


-- Engagement Activity
CREATE TABLE engagement_activity (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action_type VARCHAR(50),
    comment_id VARCHAR(255),
    video_id VARCHAR(255),
    created_comment_id VARCHAR(255),
    content TEXT,
    timestamp TIMESTAMP
);

CREATE INDEX idx_engagement_activity_user_id ON engagement_activity(user_id);
CREATE INDEX idx_engagement_user_time ON engagement_activity(user_id, timestamp DESC);


-- Payment Audits
CREATE TABLE payment_audits (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    merchant_user_id VARCHAR(255), -- Keep for reference
    transaction_id VARCHAR(255) UNIQUE NOT NULL,
    amount BIGINT,
    status VARCHAR(50),
    provider_reference_id VARCHAR(255),
    raw_response TEXT,
    plan_id VARCHAR(255),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX idx_payment_audits_user_id ON payment_audits(user_id);


-- Quota Audit
CREATE TABLE quota_audit (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    api_name VARCHAR(255),
    cost INTEGER,
    timestamp TIMESTAMP,
    status VARCHAR(50)
);

CREATE INDEX idx_quota_audit_user_id ON quota_audit(user_id);


-- Processed Comments
CREATE TABLE processed_comments (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    comment_id VARCHAR(255),
    video_id VARCHAR(255),
    processed_at TIMESTAMP,
    sentiment VARCHAR(50),
    action_taken VARCHAR(50)
);

CREATE INDEX idx_processed_comments_user_id ON processed_comments(user_id);


-- Support Tickets
CREATE TABLE support_tickets (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    category VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    attachment_key VARCHAR(255),
    attachment_name VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_support_tickets_user_id ON support_tickets(user_id);


-- App Settings (Key-Value)
CREATE TABLE app_settings (
    setting_key VARCHAR(255) PRIMARY KEY,
    setting_value VARCHAR(255)
);

-- Plan Configs
CREATE TABLE plan_configs (
    plan_type VARCHAR(50) PRIMARY KEY,
    display_name VARCHAR(255),
    price VARCHAR(50),
    billing_cycle VARCHAR(50),
    max_storage_bytes BIGINT,
    max_scheduled_posts INTEGER,
    max_active_streams INTEGER,
    max_channels INTEGER,
    max_resolution INTEGER
);

-- ETag Store
CREATE TABLE etag_store (
    id VARCHAR(255) PRIMARY KEY,
    etag VARCHAR(255),
    last_updated TIMESTAMP
);
