--liquibase formatted sql

--changeset afklive:085-go-live-check
--comment: GR-9 go-live check. Expand only: the previous release never reads or writes these columns.
--comment: streams.youtube_title / thumbnail_media_id: the title and thumbnail the creator uses on YouTube, typed
--comment: into AFK Live so the check can read them (stream-key streams never fetch them from YouTube).
--comment: streams.check_dismissed: comma-separated flag ids the creator chose to keep; check_weekly_at: when the
--comment: weekly check last ran (claimed with a conditional update, so only one web instance sends it).
--comment: media_assets.still_picture: true when frames sampled from start to end are the same picture; null
--comment: until measured (still_checked marks files measured or tried).
ALTER TABLE streams ADD COLUMN youtube_title VARCHAR(100);
ALTER TABLE streams ADD COLUMN thumbnail_media_id BIGINT REFERENCES media_assets(id) ON DELETE SET NULL;
ALTER TABLE streams ADD COLUMN check_dismissed VARCHAR(500);
ALTER TABLE streams ADD COLUMN check_weekly_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE media_assets ADD COLUMN still_picture BOOLEAN;
ALTER TABLE media_assets ADD COLUMN still_checked BOOLEAN NOT NULL DEFAULT FALSE;
