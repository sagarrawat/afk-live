--liquibase formatted sql

--changeset afklive:100-encoder-preset
--comment: FinOps: the live encoder's x264 preset is configurable (APP_STREAMING_X264PRESET, default superfast). A profile
--comment: copy (copy mode, FO-2) records the preset it was made with, so a worker only splices in copies whose stream looks
--comment: exactly like its own encoder's. NULL means made before this change, with veryfast. Expand only: the previous
--comment: release neither reads nor writes it.
ALTER TABLE media_playout_copies ADD COLUMN x264_preset VARCHAR(16);
