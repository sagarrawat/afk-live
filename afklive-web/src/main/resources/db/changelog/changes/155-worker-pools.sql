--liquibase formatted sql

--changeset afklive:155-worker-pools
--comment: FO-8: worker pools. Each worker reports the pool it belongs to (NULL: a worker that predates pools,
--comment: which behaves like the default pool "main"). Each run records its placement (PHONE, PLAYOUT,
--comment: PLAYLIST, OTHER; NULL: not known yet, treated like PHONE), which workers match at claim time: phone
--comment: runs only on pools that can read the ingest server, 24/7 playlists preferably on a playout pool.
--comment: The web app sets it when it creates a run; the owning worker keeps it current. Expand only.
ALTER TABLE stream_workers ADD COLUMN pool VARCHAR(40);
ALTER TABLE stream_jobs ADD COLUMN placement VARCHAR(16);
