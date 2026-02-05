CREATE TABLE IF NOT EXISTS oauth2_authorized_client (
  client_registration_id varchar(100) NOT NULL,
  principal_name varchar(200) NOT NULL,
  access_token_type varchar(100) NOT NULL,
  access_token_value bytea NOT NULL,
  access_token_issued_at timestamp NOT NULL,
  access_token_expires_at timestamp NOT NULL,
  access_token_scopes varchar(1000) DEFAULT NULL,
  refresh_token_value bytea DEFAULT NULL,
  refresh_token_issued_at timestamp DEFAULT NULL,
  created_at timestamp DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (client_registration_id, principal_name)
);

-- PostgreSQL Full Text Search Indexes
-- These creation statements are idempotent (IF NOT EXISTS)

-- Optimize video search by title
CREATE INDEX IF NOT EXISTS idx_schedvideo_title_fts ON scheduled_videos USING GIN (to_tsvector('english', coalesce(title, '')));

-- Optimize user search by username and full name
CREATE INDEX IF NOT EXISTS idx_user_search_fts ON users USING GIN (to_tsvector('english', username || ' ' || coalesce(full_name, '')));
