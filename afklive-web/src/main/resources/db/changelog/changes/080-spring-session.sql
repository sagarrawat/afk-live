--liquibase formatted sql

--changeset afklive:080-spring-session
--comment: OPS-4: sign-ins stored in PostgreSQL (Spring Session JDBC) so both web slots share them and a deploy
--comment: signs nobody out. Spring Session's own PostgreSQL schema. Expand only: the previous release keeps its
--comment: sessions in memory and never touches these tables. IF NOT EXISTS because a database that ran the app
--comment: before Spring Boot 4 may already have them (spring.session.jdbc.initialize-schema created them then).
CREATE TABLE IF NOT EXISTS spring_session (
    primary_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INT NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS spring_session_ix1 ON spring_session (session_id);
CREATE INDEX IF NOT EXISTS spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX IF NOT EXISTS spring_session_ix3 ON spring_session (principal_name);

-- Sign-ins are named by email, which may be up to 255 characters (users.email); Spring's default is 100.
ALTER TABLE spring_session ALTER COLUMN principal_name TYPE VARCHAR(255);

CREATE TABLE IF NOT EXISTS spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id) REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
