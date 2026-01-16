-- Mock Data for Development
-- Ensure clean slate for mock data to avoid duplicates if schema isn't dropped
DELETE FROM social_channels WHERE user_id = 'test@example.com';
DELETE FROM users WHERE username = 'test@example.com';

-- User: test@example.com / password
-- User: test@example.com / password (ADMIN)
INSERT INTO users (username, password, enabled, plan_type, full_name, used_storage_bytes, role)
VALUES ('test@example.com', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', true, 'FREE', 'Test User', 0, 'ROLE_ADMIN');

-- Mock Channel
INSERT INTO social_channels (name, platform, profile_url, user_id)
VALUES ('Test Channel', 'YOUTUBE', 'https://ui-avatars.com/api/?name=Test+Channel&background=random', 'test@example.com');
