-- Mock Data for Development

-- Insert Admin User if not exists
INSERT INTO users (email, password, enabled, plan_type, full_name, used_storage_bytes, role, credit_limit, unpaid_balance)
VALUES ('test@example.com', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', true, 'FREE', 'Test User', 0, 'ROLE_ADMIN', 100, 0)
ON CONFLICT (email) DO NOTHING;

-- Insert User Settings for Admin
INSERT INTO user_settings (user_id, auto_reply_enabled, delete_negative_comments, auto_reply_unreplied_enabled)
SELECT id, false, false, false
FROM users WHERE email = 'test@example.com'
ON CONFLICT (user_id) DO NOTHING;

-- Insert Mock Channel for Admin User
INSERT INTO social_channels (name, platform, profile_url, user_id)
SELECT 'Test Channel', 'YOUTUBE', 'https://ui-avatars.com/api/?name=Test+Channel&background=random', id
FROM users WHERE email = 'test@example.com'
AND NOT EXISTS (SELECT 1 FROM social_channels WHERE user_id = users.id AND name = 'Test Channel');

-- Insert Mock Media Asset
INSERT INTO media_assets (user_id, title, s3_key, file_size, content_type, stream_readiness)
SELECT id, 'mock_video.mp4', 'mock_video_s3_key', 102400, 'VIDEO', 'PENDING_ANALYSIS'
FROM users WHERE email = 'test@example.com'
AND NOT EXISTS (SELECT 1 FROM media_assets WHERE s3_key = 'mock_video_s3_key');

-- Insert Test User
INSERT INTO users (email, password, enabled, plan_type, full_name, used_storage_bytes, role, credit_limit, unpaid_balance)
VALUES ('test1@afklive.in', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', true, 'FREE', 'Test1 User', 0, 'ROLE_USER', 100, 0)
ON CONFLICT (email) DO NOTHING;

-- Insert User Settings for Test1 User
INSERT INTO user_settings (user_id, auto_reply_enabled, delete_negative_comments, auto_reply_unreplied_enabled)
SELECT id, false, false, false
FROM users WHERE email = 'test1@afklive.in'
ON CONFLICT (user_id) DO NOTHING;
