-- Mock Data for Development

-- Insert Admin User if not exists
INSERT INTO users (email, password, enabled, plan_type, full_name, used_storage_bytes, role, auto_reply_enabled, delete_negative_comments)
VALUES ('test@example.com', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', true, 'FREE', 'Test User', 0, 'ROLE_ADMIN', false, false)
ON CONFLICT (email) DO NOTHING;

-- Insert Mock Channel for Admin User
INSERT INTO social_channels (name, platform, profile_url, user_id)
SELECT 'Test Channel', 'YOUTUBE', 'https://ui-avatars.com/api/?name=Test+Channel&background=random', id
FROM users WHERE email = 'test@example.com'
AND NOT EXISTS (SELECT 1 FROM social_channels WHERE user_id = users.id AND name = 'Test Channel');
