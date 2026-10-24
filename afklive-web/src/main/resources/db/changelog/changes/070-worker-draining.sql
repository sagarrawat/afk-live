--liquibase formatted sql

--changeset afklive:070-worker-draining
--comment: OPS-3: a worker being replaced by a new version is marked draining here (set through its own
--comment: localhost control endpoint by the deploy script). A draining worker takes on no new streams and
--comment: hands the ones it runs to another worker, one at a time. NULL: not draining. Expand only.
ALTER TABLE stream_workers ADD COLUMN draining_since TIMESTAMP WITH TIME ZONE;
