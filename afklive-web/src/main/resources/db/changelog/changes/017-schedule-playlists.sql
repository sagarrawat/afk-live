--liquibase formatted sql

--changeset afklive:017-schedule-playlists
--comment: SC-4: a schedule slot can play its own list of videos; the run remembers it so live edits keep it.
ALTER TABLE stream_schedules ADD COLUMN playlist TEXT;
ALTER TABLE stream_jobs ADD COLUMN playlist_override TEXT;
