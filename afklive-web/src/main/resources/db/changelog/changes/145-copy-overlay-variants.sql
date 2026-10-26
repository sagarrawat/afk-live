--liquibase formatted sql

--changeset afklive:145-copy-overlay-variants
--comment: FO-2: profile copies with static overlays (the Free plan's mark, a fixed logo or text) baked in, so those
--comment: runs can play by copy too. overlay_set is a hash of what is drawn ('' = nothing: every existing copy);
--comment: overlay_spec is what to draw (JSON, for the web side that makes the copy); made_overlay_set is written by the
--comment: maker once the overlays are in the file, so a copy made by a release that can't draw them is never played.
--comment: Copies with overlays are kind PROFILE_OVERLAY, which the previous release never looks up (it asks for kind
--comment: PROFILE only). Expand only: new columns have defaults or are nullable; the unique key gains overlay_set,
--comment: and the previous release's inserts (overlay_set '') conflict exactly as before.
ALTER TABLE media_playout_copies ADD COLUMN overlay_set VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE media_playout_copies ADD COLUMN overlay_spec TEXT;
ALTER TABLE media_playout_copies ADD COLUMN made_overlay_set VARCHAR(64);
ALTER TABLE media_playout_copies DROP CONSTRAINT uq_media_playout_copies;
ALTER TABLE media_playout_copies ADD CONSTRAINT uq_media_playout_copies
    UNIQUE (media_id, kind, canvas_width, canvas_height, fps, fit, gain_tenths, overlay_set);
