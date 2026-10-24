--liquibase formatted sql

--changeset afklive:066-worker-cpus
--comment: GL-7: workers report how many CPUs they have, so the web side can tell whether a new stream's
--comment: quality fits the free capacity. Nullable: workers on the previous release don't report it (the web
--comment: then assumes one CPU per stream slot).
ALTER TABLE stream_workers ADD COLUMN cpus INT;
