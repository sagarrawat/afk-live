--liquibase formatted sql

--changeset afklive:021-status
--comment: RE-7: the public status page. Admins post incidents with timestamped updates; platform signals
--comment: (the ingest server's last auth call) are shared across web instances. AD-2: each stream worker
--comment: reports itself (CPU, memory, streams, capacity) so admins see load per machine, idle ones included.
CREATE TABLE status_incidents (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    resolved_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_status_incidents_created ON status_incidents(created_at DESC);

CREATE TABLE status_incident_updates (
    id BIGSERIAL PRIMARY KEY,
    incident_id BIGINT NOT NULL REFERENCES status_incidents(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    message TEXT NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_status_incident_updates_incident ON status_incident_updates(incident_id, created_at);

CREATE TABLE status_signals (
    name VARCHAR(40) PRIMARY KEY,
    seen_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE stream_workers (
    worker_id VARCHAR(100) PRIMARY KEY,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL,
    streams INTEGER NOT NULL DEFAULT 0,
    capacity INTEGER NOT NULL DEFAULT 0,
    cpu_percent DOUBLE PRECISION,
    memory_used_bytes BIGINT,
    memory_total_bytes BIGINT
);
