--liquibase formatted sql

--changeset afklive:150-copy-bitrate splitStatements:false
--comment: FO-4: the live video bitrate can differ per plan, and copy mode must never splice a profile copy made at
--comment: another bitrate into a stream. video_kbps is the bitrate a profile copy was made at; 0 = the built-in
--comment: ladder's (720p 4 Mbps, 1080p 6 Mbps, 60 fps x1.5), which is what every existing copy was made at and what
--comment: the new release stores for runs at that bitrate, so old and new releases share those rows. (NOT NULL with
--comment: a default rather than NULL, so the previous release's ON CONFLICT inserts still conflict.)
--comment: Expand only: the unique key gains video_kbps, appended to whatever columns it has now (other changesets
--comment: may have extended it), and keeps its name, which the previous release's inserts refer to.
ALTER TABLE media_playout_copies ADD COLUMN video_kbps INT NOT NULL DEFAULT 0;
DO $$
DECLARE
    cols TEXT;
BEGIN
    SELECT string_agg(quote_ident(a.attname), ', ' ORDER BY k.ord)
      INTO cols
      FROM pg_constraint c
      CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
     WHERE c.conname = 'uq_media_playout_copies' AND c.conrelid = 'media_playout_copies'::regclass;
    IF cols IS NULL THEN
        RAISE EXCEPTION 'uq_media_playout_copies not found';
    END IF;
    IF position('video_kbps' IN cols) = 0 THEN
        EXECUTE 'ALTER TABLE media_playout_copies DROP CONSTRAINT uq_media_playout_copies';
        EXECUTE 'ALTER TABLE media_playout_copies ADD CONSTRAINT uq_media_playout_copies UNIQUE (' || cols || ', video_kbps)';
    END IF;
END $$;
