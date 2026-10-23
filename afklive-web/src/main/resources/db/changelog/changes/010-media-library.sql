--liquibase formatted sql

--changeset afklive:010-media-library
--comment: ML-1..ML-6: resumable uploads (storage reserved up front), processing claims, thumbnails.
ALTER TABLE media_assets ADD COLUMN thumbnail_key VARCHAR(512);
ALTER TABLE media_assets ADD COLUMN processing_claimed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE media_assets ADD COLUMN fps DOUBLE PRECISION;
CREATE INDEX idx_media_assets_processing ON media_assets(status) WHERE status = 'PROCESSING';

CREATE TABLE media_uploads (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(20) NOT NULL,
    size_bytes BIGINT NOT NULL,
    part_size BIGINT NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    multipart_id VARCHAR(1024),
    status VARCHAR(20) NOT NULL DEFAULT 'UPLOADING',
    media_asset_id BIGINT REFERENCES media_assets(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_media_uploads_user ON media_uploads(user_id, status);
CREATE INDEX idx_media_uploads_expiry ON media_uploads(expires_at) WHERE status = 'UPLOADING';
