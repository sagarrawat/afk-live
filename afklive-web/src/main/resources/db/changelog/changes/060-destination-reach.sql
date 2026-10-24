--liquibase formatted sql

--changeset afklive:060-destination-reach
--comment: RE-8: whether each destination's platform is receiving the stream (ON, CONNECTING, NOT_REACHING) and
--comment: why not (the relay's last error, keys redacted), written by the worker as it changes. FO-3: bytes each
--comment: destination actually sent during the run, written with the heartbeat. Expand only: the previous worker
--comment: leaves them empty and the web falls back to the relay status.
ALTER TABLE stream_job_destinations ADD COLUMN reach VARCHAR(20);
ALTER TABLE stream_job_destinations ADD COLUMN reach_reason TEXT;
ALTER TABLE stream_job_destinations ADD COLUMN bytes_sent BIGINT NOT NULL DEFAULT 0;
