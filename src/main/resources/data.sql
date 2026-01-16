-- Mock Data for Development
-- User: test@example.com / password
INSERT INTO users (username, password, enabled, plan_type, full_name, used_storage_bytes)
VALUES ('test@example.com', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', true, 'FREE', 'Test User', 0);

-- Mock Channel
INSERT INTO social_channels (name, platform, profile_url, user_id)
VALUES ('Test Channel', 'YOUTUBE', 'https://ui-avatars.com/api/?name=Test+Channel&background=random', 'test@example.com');
