--liquibase formatted sql

--changeset afklive:004-scheduled-stream-asset-on-delete
--comment: Deleting a library video must not fail because a scheduled stream points at it; the schedule then fails with a clear message.
ALTER TABLE scheduled_streams DROP CONSTRAINT IF EXISTS scheduled_streams_media_asset_id_fkey;
ALTER TABLE scheduled_streams ADD CONSTRAINT scheduled_streams_media_asset_id_fkey
    FOREIGN KEY (media_asset_id) REFERENCES media_assets(id) ON DELETE SET NULL;
