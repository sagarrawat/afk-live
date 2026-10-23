--liquibase formatted sql

--changeset afklive:045-profile-copies
--comment: FO-2 copy mode: besides playout copies (a smaller file the studio decodes), a "profile" copy is the
--comment: file encoded exactly as the stream sends it (canvas, frame rate, fit, loudness, bitrate), so a
--comment: playlist can play by stream copy. Expand only: the previous release keeps working (its rows are PLAYOUT).
ALTER TABLE media_playout_copies ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'PLAYOUT';
ALTER TABLE media_playout_copies ADD COLUMN fit VARCHAR(8) NOT NULL DEFAULT '';
ALTER TABLE media_playout_copies ADD COLUMN gain_tenths INT NOT NULL DEFAULT 0;
ALTER TABLE media_playout_copies DROP CONSTRAINT uq_media_playout_copies;
ALTER TABLE media_playout_copies ADD CONSTRAINT uq_media_playout_copies
    UNIQUE (media_id, kind, canvas_width, canvas_height, fps, fit, gain_tenths);
