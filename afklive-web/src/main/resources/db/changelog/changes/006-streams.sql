--liquibase formatted sql

--changeset afklive:006-streams
--comment: A stream is a saved broadcast (draft until it goes live); each time it goes live is a stream_job.
--comment: Media gets the facts playout needs (duration, size, audio) so playlists can preroll and be checked.
ALTER TABLE media_assets ADD COLUMN duration_seconds DOUBLE PRECISION;
ALTER TABLE media_assets ADD COLUMN width INTEGER;
ALTER TABLE media_assets ADD COLUMN height INTEGER;
ALTER TABLE media_assets ADD COLUMN video_codec VARCHAR(30);
ALTER TABLE media_assets ADD COLUMN has_audio BOOLEAN;
ALTER TABLE media_assets ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'READY';
ALTER TABLE media_assets ADD COLUMN status_reason TEXT;

CREATE TABLE streams (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    source VARCHAR(20) NOT NULL DEFAULT 'PLAYLIST',
    orientation VARCHAR(20) NOT NULL DEFAULT 'LANDSCAPE',
    quality_height INTEGER NOT NULL DEFAULT 720,
    fps INTEGER NOT NULL DEFAULT 30,
    playback_order VARCHAR(20) NOT NULL DEFAULT 'IN_ORDER',
    loop_count INTEGER NOT NULL DEFAULT 0,
    music_order VARCHAR(20) NOT NULL DEFAULT 'IN_ORDER',
    audio_mode VARCHAR(10) NOT NULL DEFAULT 'KEEP',
    video_volume DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    music_volume DOUBLE PRECISION NOT NULL DEFAULT 0.6,
    overlays TEXT,
    scenes TEXT,
    time_zone VARCHAR(64) NOT NULL DEFAULT 'UTC',
    resume_position TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_streams_user ON streams(user_id);

CREATE TABLE stream_items (
    id BIGSERIAL PRIMARY KEY,
    stream_id BIGINT NOT NULL REFERENCES streams(id) ON DELETE CASCADE,
    kind VARCHAR(10) NOT NULL,
    position INTEGER NOT NULL,
    media_asset_id BIGINT NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE
);
CREATE INDEX idx_stream_items_stream ON stream_items(stream_id, kind, position);
CREATE INDEX idx_stream_items_media ON stream_items(media_asset_id);

CREATE TABLE stream_targets (
    stream_id BIGINT NOT NULL REFERENCES streams(id) ON DELETE CASCADE,
    destination_id BIGINT NOT NULL REFERENCES stream_destinations(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (stream_id, destination_id)
);

CREATE TABLE stream_schedules (
    id BIGSERIAL PRIMARY KEY,
    stream_id BIGINT NOT NULL REFERENCES streams(id) ON DELETE CASCADE,
    kind VARCHAR(10) NOT NULL,
    start_at TIMESTAMP WITH TIME ZONE,
    end_at TIMESTAMP WITH TIME ZONE,
    days_of_week VARCHAR(30),
    start_time TIME,
    end_time TIME,
    time_zone VARCHAR(64) NOT NULL DEFAULT 'UTC',
    skip_dates TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_started_on DATE,
    last_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_stream_schedules_enabled ON stream_schedules(enabled) WHERE enabled = TRUE;

-- Live changes from the web (scene switch, overlay toggle, skip, playlist edits) for the worker to apply.
CREATE TABLE stream_commands (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES stream_jobs(id) ON DELETE CASCADE,
    type VARCHAR(30) NOT NULL,
    payload TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_stream_commands_job ON stream_commands(job_id, id);

ALTER TABLE stream_jobs ADD COLUMN stream_id BIGINT REFERENCES streams(id) ON DELETE SET NULL;
ALTER TABLE stream_jobs ADD COLUMN scene VARCHAR(30);
ALTER TABLE stream_jobs ADD COLUMN input_live BOOLEAN;
ALTER TABLE stream_jobs ADD COLUMN now_playing TEXT;
ALTER TABLE stream_jobs ADD COLUMN applied_command_id BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_stream_jobs_stream ON stream_jobs(stream_id);
