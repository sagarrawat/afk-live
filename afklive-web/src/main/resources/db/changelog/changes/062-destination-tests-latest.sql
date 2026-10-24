--liquibase formatted sql

--changeset afklive:062-destination-tests-latest
--comment: GL-1: Go live looks up each destination's latest tests.
CREATE INDEX idx_destination_tests_destination ON destination_tests(destination_id, id DESC);
