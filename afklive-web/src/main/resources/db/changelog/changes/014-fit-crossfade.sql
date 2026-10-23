--liquibase formatted sql

--changeset afklive:014-fit-crossfade
--comment: PL-6 / MS-10: how pictures fill the frame, per stream and per playlist item. AU-3: music crossfade.
ALTER TABLE streams ADD COLUMN fit VARCHAR(10) NOT NULL DEFAULT 'FIT';
ALTER TABLE streams ADD COLUMN crossfade_seconds INTEGER NOT NULL DEFAULT 0;
ALTER TABLE stream_items ADD COLUMN fit VARCHAR(10);
