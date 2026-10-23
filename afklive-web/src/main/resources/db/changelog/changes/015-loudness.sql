--liquibase formatted sql

--changeset afklive:015-loudness
--comment: AU-5: each file's integrated loudness (EBU R128, LUFS), so playout brings every track to one level.
ALTER TABLE media_assets ADD COLUMN loudness_lufs DOUBLE PRECISION;
ALTER TABLE media_assets ADD COLUMN loudness_checked BOOLEAN NOT NULL DEFAULT FALSE;
