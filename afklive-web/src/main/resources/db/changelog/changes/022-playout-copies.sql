--liquibase formatted sql

--changeset afklive:022-playout-copies
--comment: Playout copies: a library video prepared once at the size and frame rate a stream uses (say 720x1280
--comment: at 30 fps), so live streams decode a small file instead of shrinking a 1080p60 one every second.
--comment: Requested by workers when they play a file that would benefit; made by the web side in the background.
CREATE TABLE media_playout_copies (
    id BIGSERIAL PRIMARY KEY,
    media_id BIGINT NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,
    canvas_width INT NOT NULL,
    canvas_height INT NOT NULL,
    fps INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    storage_key VARCHAR(512),
    reason TEXT,
    claimed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    finished_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_media_playout_copies UNIQUE (media_id, canvas_width, canvas_height, fps)
);
CREATE INDEX idx_media_playout_copies_pending ON media_playout_copies(status, id);
