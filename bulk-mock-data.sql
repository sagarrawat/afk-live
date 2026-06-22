DO $$
DECLARE
    i INT;
    j INT;
    num_streams INT;
    new_user_id BIGINT;
    user_email VARCHAR;
    cost_amount DOUBLE PRECISION;
    start_ts TIMESTAMP WITH TIME ZONE;
    end_ts TIMESTAMP WITH TIME ZONE;
BEGIN
    FOR i IN 1..200 LOOP
        user_email := 'mockuser' || i || '@example.com';
        
        -- Insert user
        INSERT INTO users (email, password, enabled, plan_type, full_name, role, credit_limit, unpaid_balance, currency, used_storage_bytes)
        VALUES (
            user_email,
            '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG',
            true,
            'FREE',
            'Mock User ' || i,
            'ROLE_USER',
            100.0,
            0,
            'INR',
            0
        ) RETURNING id INTO new_user_id;

        -- Settings
        INSERT INTO user_settings (user_id, auto_reply_enabled, delete_negative_comments, auto_reply_unreplied_enabled)
        VALUES (new_user_id, false, false, false);
        
        -- Social Channel
        INSERT INTO social_channels (name, platform, profile_url, user_id)
        VALUES ('Channel ' || i, 'YOUTUBE', 'https://youtube.com/channel/' || i, new_user_id);
        
        -- Destinations
        INSERT INTO stream_destinations (user_id, name, stream_key, type, selected)
        VALUES (new_user_id, 'Youtube Dest', 'live2-mock-key-' || i, 'YOUTUBE', true);

        num_streams := floor(random() * 19 + 2)::int; -- 2 to 20 streams
        
        FOR j IN 1..num_streams LOOP
            cost_amount := random() * 10;
            start_ts := current_timestamp - (random() * 30 || ' days')::interval - (random() * 24 || ' hours')::interval;
            end_ts := start_ts + (random() * 4 || ' hours')::interval;
            
            INSERT INTO stream_jobs (
                user_id, stream_key, status, start_time, end_time, cost, accumulated_cost, 
                locked_hourly_rate, title, bytes_streamed, destination_name, auto_reply_enabled, retry_count, max_retries, dropped_frames
            ) VALUES (
                new_user_id,
                'live2-mock-key-' || i,
                'COMPLETED',
                start_ts,
                end_ts,
                cost_amount,
                cost_amount,
                1.42,
                'Mock Stream ' || j || ' for User ' || i,
                floor(random() * 1000000000)::bigint,
                'Youtube Dest',
                false,
                0,
                3,
                0
            );
        END LOOP;

        -- Update user balance with total sum of a random set of unpaid streams
        UPDATE users 
        SET unpaid_balance = (
            SELECT COALESCE(SUM(cost), 0) FROM stream_jobs 
            WHERE stream_jobs.user_id = users.id AND random() < 0.2
        )
        WHERE id = new_user_id;

    END LOOP;
END $$;
