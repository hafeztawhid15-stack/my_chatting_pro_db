-- ============================================================================
-- MY CHATTING PRO — Atomic PostgreSQL RPC Functions (Migration 0003)
-- Callable strictly via service_role from Cloudflare Workers Gateway.
-- ============================================================================

-- 1. Atomic Sliding-Window Rate Limiter
CREATE OR REPLACE FUNCTION public.consume_rate_limit(
    p_bucket_key TEXT,
    p_max_requests INTEGER,
    p_window_ms BIGINT,
    p_block_ms BIGINT,
    p_now_ms BIGINT
)
RETURNS TABLE (allowed BOOLEAN, retry_after_seconds INTEGER)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_row public.rate_limits%ROWTYPE;
    v_next_count INTEGER;
    v_blocked_until BIGINT;
BEGIN
    SELECT * INTO v_row FROM public.rate_limits WHERE bucket_key = p_bucket_key FOR UPDATE;

    IF FOUND AND v_row.blocked_until_ms IS NOT NULL AND v_row.blocked_until_ms > p_now_ms THEN
        RETURN QUERY SELECT FALSE, GREATEST(1, CEIL((v_row.blocked_until_ms - p_now_ms)::NUMERIC / 1000.0)::INTEGER);
        RETURN;
    END IF;

    IF NOT FOUND OR (p_now_ms - v_row.window_start_ms) > p_window_ms THEN
        INSERT INTO public.rate_limits (bucket_key, count, window_start_ms, blocked_until_ms)
        VALUES (p_bucket_key, 1, p_now_ms, NULL)
        ON CONFLICT (bucket_key) DO UPDATE
        SET count = 1,
            window_start_ms = EXCLUDED.window_start_ms,
            blocked_until_ms = NULL;

        RETURN QUERY SELECT TRUE, 0;
        RETURN;
    END IF;

    v_next_count := v_row.count + 1;
    IF v_next_count > p_max_requests THEN
        v_blocked_until := p_now_ms + p_block_ms;
        UPDATE public.rate_limits
        SET count = v_next_count,
            blocked_until_ms = v_blocked_until
        WHERE bucket_key = p_bucket_key;

        RETURN QUERY SELECT FALSE, GREATEST(1, CEIL((v_blocked_until - p_now_ms)::NUMERIC / 1000.0)::INTEGER);
        RETURN;
    END IF;

    UPDATE public.rate_limits
    SET count = v_next_count
    WHERE bucket_key = p_bucket_key;

    RETURN QUERY SELECT TRUE, 0;
END;
$$;

-- 2. Verify Active Session + User Role
CREATE OR REPLACE FUNCTION public.verify_active_session(
    p_session_id TEXT,
    p_user_id TEXT
)
RETURNS TABLE (
    session_id TEXT,
    revoked_at_ms BIGINT,
    account_status TEXT,
    user_role TEXT
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT s.id AS session_id, s.revoked_at_ms, u.account_status, u.role AS user_role
    FROM public.sessions s
    JOIN public.users u ON u.id = s.user_id
    WHERE s.id = p_session_id AND s.user_id = p_user_id
    LIMIT 1;
$$;

-- 3. Register User + Profile + Device + Session in single atomic transaction
CREATE OR REPLACE FUNCTION public.register_user_account(
    p_user_id TEXT,
    p_phone_e164 TEXT,
    p_country_iso2 TEXT,
    p_calling_code TEXT,
    p_pin_hash TEXT,
    p_display_name TEXT,
    p_device_id TEXT,
    p_device_name TEXT,
    p_app_version TEXT,
    p_session_id TEXT,
    p_refresh_hash TEXT,
    p_expires_at_ms BIGINT,
    p_now_ms BIGINT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.users WHERE phone_e164 = p_phone_e164) THEN
        RAISE EXCEPTION 'duplicate_account' USING ERRCODE = '23505';
    END IF;

    INSERT INTO public.users (
        id, phone_e164, country_iso2, calling_code, pin_hash,
        role, phone_verified, account_status, created_at_ms, updated_at_ms
    )
    VALUES (
        p_user_id, p_phone_e164, p_country_iso2, p_calling_code, p_pin_hash,
        'user', FALSE, 'active', p_now_ms, p_now_ms
    );

    INSERT INTO public.user_profiles (
        user_id, display_name, about_text, is_online, last_seen_ms, updated_at_ms
    )
    VALUES (
        p_user_id, p_display_name, 'Available on MY CHATTING PRO', FALSE, p_now_ms, p_now_ms
    );

    INSERT INTO public.devices (
        id, user_id, device_name, platform, app_version, last_active_ms, created_at_ms
    )
    VALUES (
        p_device_id, p_user_id, p_device_name, 'android', p_app_version, p_now_ms, p_now_ms
    );

    INSERT INTO public.sessions (
        id, user_id, device_id, refresh_token_hash, expires_at_ms, last_used_at_ms, created_at_ms
    )
    VALUES (
        p_session_id, p_user_id, p_device_id, p_refresh_hash, p_expires_at_ms, p_now_ms, p_now_ms
    );
END;
$$;

-- 4. Complete Login Session Reset + Device + Session in single transaction
CREATE OR REPLACE FUNCTION public.complete_login_session(
    p_user_id TEXT,
    p_device_id TEXT,
    p_device_name TEXT,
    p_app_version TEXT,
    p_session_id TEXT,
    p_refresh_hash TEXT,
    p_expires_at_ms BIGINT,
    p_now_ms BIGINT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    UPDATE public.users
    SET failed_login_count = 0,
        locked_until_ms = NULL,
        updated_at_ms = p_now_ms
    WHERE id = p_user_id;

    INSERT INTO public.devices (
        id, user_id, device_name, platform, app_version, last_active_ms, created_at_ms
    )
    VALUES (
        p_device_id, p_user_id, p_device_name, 'android', p_app_version, p_now_ms, p_now_ms
    );

    INSERT INTO public.sessions (
        id, user_id, device_id, refresh_token_hash, expires_at_ms, last_used_at_ms, created_at_ms
    )
    VALUES (
        p_session_id, p_user_id, p_device_id, p_refresh_hash, p_expires_at_ms, p_now_ms, p_now_ms
    );
END;
$$;

-- 5. List Active Sessions for User
CREATE OR REPLACE FUNCTION public.list_active_user_sessions(
    p_user_id TEXT,
    p_now_ms BIGINT
)
RETURNS TABLE (
    session_id TEXT,
    device_id TEXT,
    device_name TEXT,
    platform TEXT,
    app_version TEXT,
    last_used_at_ms BIGINT,
    created_at_ms BIGINT
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT
        s.id AS session_id,
        s.device_id,
        d.device_name,
        d.platform,
        d.app_version,
        s.last_used_at_ms,
        s.created_at_ms
    FROM public.sessions s
    JOIN public.devices d ON d.id = s.device_id
    WHERE s.user_id = p_user_id
      AND s.revoked_at_ms IS NULL
      AND s.expires_at_ms > p_now_ms
    ORDER BY s.last_used_at_ms DESC;
$$;

-- 6. Search Active Registered User by Phone (+ optional Name filter)
CREATE OR REPLACE FUNCTION public.search_public_user_by_phone(
    p_requester_id TEXT,
    p_phone_e164 TEXT,
    p_name_filter TEXT DEFAULT NULL
)
RETURNS TABLE (
    id TEXT,
    display_name TEXT,
    about_text TEXT,
    avatar_media_id TEXT,
    is_online BOOLEAN,
    last_seen_ms BIGINT
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT
        u.id,
        p.display_name,
        p.about_text,
        p.avatar_media_id,
        p.is_online,
        p.last_seen_ms
    FROM public.users u
    JOIN public.user_profiles p ON p.user_id = u.id
    LEFT JOIN public.blocked_users b1
        ON b1.blocker_id = u.id AND b1.blocked_id = p_requester_id
    WHERE u.phone_e164 = p_phone_e164
      AND u.account_status = 'active'
      AND u.id <> p_requester_id
      AND b1.blocker_id IS NULL
      AND (
          p_name_filter IS NULL
          OR BTRIM(p_name_filter) = ''
          OR p.display_name ILIKE '%' || BTRIM(p_name_filter) || '%'
      )
    LIMIT 1;
$$;

-- 7. List User Conversations
CREATE OR REPLACE FUNCTION public.list_user_conversations(p_user_id TEXT)
RETURNS TABLE (
    id TEXT,
    type TEXT,
    last_message_id TEXT,
    last_message_preview TEXT,
    last_message_type TEXT,
    last_message_sender_id TEXT,
    last_message_at_ms BIGINT,
    unread_count INTEGER,
    updated_at_ms BIGINT,
    peer_user_id TEXT,
    peer_display_name TEXT,
    peer_avatar_media_id TEXT,
    peer_about_text TEXT,
    peer_is_online BOOLEAN,
    peer_last_seen_ms BIGINT,
    blocked_by_me BOOLEAN,
    blocked_me BOOLEAN
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT
        c.id,
        c.type,
        c.last_message_id,
        c.last_message_preview,
        c.last_message_type,
        c.last_message_sender_id,
        c.last_message_at_ms,
        my_cm.unread_count,
        c.updated_at_ms,
        peer_cm.user_id AS peer_user_id,
        p.display_name AS peer_display_name,
        p.avatar_media_id AS peer_avatar_media_id,
        p.about_text AS peer_about_text,
        p.is_online AS peer_is_online,
        p.last_seen_ms AS peer_last_seen_ms,
        (b1.blocker_id IS NOT NULL) AS blocked_by_me,
        (b2.blocker_id IS NOT NULL) AS blocked_me
    FROM public.conversation_members my_cm
    JOIN public.conversations c ON c.id = my_cm.conversation_id
    JOIN public.conversation_members peer_cm
        ON peer_cm.conversation_id = c.id AND peer_cm.user_id <> my_cm.user_id
    JOIN public.users pu
        ON pu.id = peer_cm.user_id AND pu.account_status = 'active'
    JOIN public.user_profiles p ON p.user_id = peer_cm.user_id
    LEFT JOIN public.blocked_users b1
        ON b1.blocker_id = p_user_id AND b1.blocked_id = peer_cm.user_id
    LEFT JOIN public.blocked_users b2
        ON b2.blocker_id = peer_cm.user_id AND b2.blocked_id = p_user_id
    WHERE my_cm.user_id = p_user_id
    ORDER BY COALESCE(c.last_message_at_ms, c.created_at_ms) DESC;
$$;

-- 8. Get or Create Direct Conversation Atomically
CREATE OR REPLACE FUNCTION public.get_or_create_direct_conversation(
    p_current_user_id TEXT,
    p_target_user_id TEXT,
    p_new_conv_id TEXT,
    p_direct_key TEXT,
    p_now_ms BIGINT
)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_conv_id TEXT;
BEGIN
    SELECT id INTO v_conv_id FROM public.conversations WHERE direct_key = p_direct_key;
    IF v_conv_id IS NOT NULL THEN
        RETURN v_conv_id;
    END IF;

    INSERT INTO public.conversations (id, type, direct_key, created_at_ms, updated_at_ms)
    VALUES (p_new_conv_id, 'direct', p_direct_key, p_now_ms, p_now_ms)
    ON CONFLICT (direct_key) DO NOTHING;

    SELECT id INTO v_conv_id FROM public.conversations WHERE direct_key = p_direct_key;

    INSERT INTO public.conversation_members (conversation_id, user_id, role, unread_count, joined_at_ms)
    VALUES (v_conv_id, p_current_user_id, 'member', 0, p_now_ms)
    ON CONFLICT (conversation_id, user_id) DO NOTHING;

    INSERT INTO public.conversation_members (conversation_id, user_id, role, unread_count, joined_at_ms)
    VALUES (v_conv_id, p_target_user_id, 'member', 0, p_now_ms)
    ON CONFLICT (conversation_id, user_id) DO NOTHING;

    RETURN v_conv_id;
END;
$$;

-- 9. List Conversation Messages with Media Metadata & Aggregated Receipts
CREATE OR REPLACE FUNCTION public.list_conversation_messages(
    p_conversation_id TEXT,
    p_before_ts BIGINT DEFAULT NULL,
    p_before_id TEXT DEFAULT NULL,
    p_limit INTEGER DEFAULT 41
)
RETURNS TABLE (
    id TEXT,
    conversation_id TEXT,
    sender_id TEXT,
    client_message_id TEXT,
    message_type TEXT,
    body_text TEXT,
    media_id TEXT,
    reply_to_message_id TEXT,
    deleted_for_all_at_ms BIGINT,
    created_at_ms BIGINT,
    media_category TEXT,
    original_filename TEXT,
    mime_type TEXT,
    size_bytes BIGINT,
    duration_ms BIGINT,
    width INTEGER,
    height INTEGER,
    delivered_at_ms BIGINT,
    read_at_ms BIGINT
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT
        m.id,
        m.conversation_id,
        m.sender_id,
        m.client_message_id,
        m.message_type,
        m.body_text,
        m.media_id,
        m.reply_to_message_id,
        m.deleted_for_all_at_ms,
        m.created_at_ms,
        mo.category AS media_category,
        mo.original_filename,
        mo.mime_type,
        mo.size_bytes,
        mo.duration_ms,
        mo.width,
        mo.height,
        (SELECT MIN(r.delivered_at_ms) FROM public.message_receipts r WHERE r.message_id = m.id AND r.delivered_at_ms IS NOT NULL) AS delivered_at_ms,
        (SELECT MIN(r.read_at_ms) FROM public.message_receipts r WHERE r.message_id = m.id AND r.read_at_ms IS NOT NULL) AS read_at_ms
    FROM public.messages m
    LEFT JOIN public.media_objects mo ON mo.id = m.media_id
    WHERE m.conversation_id = p_conversation_id
      AND (
          p_before_ts IS NULL
          OR (
              p_before_id IS NOT NULL
              AND (m.created_at_ms < p_before_ts OR (m.created_at_ms = p_before_ts AND m.id < p_before_id))
          )
          OR (p_before_id IS NULL AND m.created_at_ms < p_before_ts)
      )
    ORDER BY m.created_at_ms DESC, m.id DESC
    LIMIT p_limit;
$$;

-- 10. Fetch Single Message with Media & Receipts
CREATE OR REPLACE FUNCTION public.get_single_message_detail(p_message_id TEXT)
RETURNS TABLE (
    id TEXT,
    conversation_id TEXT,
    sender_id TEXT,
    client_message_id TEXT,
    message_type TEXT,
    body_text TEXT,
    media_id TEXT,
    reply_to_message_id TEXT,
    deleted_for_all_at_ms BIGINT,
    created_at_ms BIGINT,
    media_category TEXT,
    original_filename TEXT,
    mime_type TEXT,
    size_bytes BIGINT,
    duration_ms BIGINT,
    width INTEGER,
    height INTEGER,
    delivered_at_ms BIGINT,
    read_at_ms BIGINT
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT
        m.id,
        m.conversation_id,
        m.sender_id,
        m.client_message_id,
        m.message_type,
        m.body_text,
        m.media_id,
        m.reply_to_message_id,
        m.deleted_for_all_at_ms,
        m.created_at_ms,
        mo.category AS media_category,
        mo.original_filename,
        mo.mime_type,
        mo.size_bytes,
        mo.duration_ms,
        mo.width,
        mo.height,
        (SELECT MIN(r.delivered_at_ms) FROM public.message_receipts r WHERE r.message_id = m.id AND r.delivered_at_ms IS NOT NULL) AS delivered_at_ms,
        (SELECT MIN(r.read_at_ms) FROM public.message_receipts r WHERE r.message_id = m.id AND r.read_at_ms IS NOT NULL) AS read_at_ms
    FROM public.messages m
    LEFT JOIN public.media_objects mo ON mo.id = m.media_id
    WHERE m.id = p_message_id
    LIMIT 1;
$$;

-- 11. Send Message Transaction (Insert Message + Update Conversation + Receipts + Unread Counts)
CREATE OR REPLACE FUNCTION public.send_chat_message(
    p_message_id TEXT,
    p_conversation_id TEXT,
    p_sender_id TEXT,
    p_client_message_id TEXT,
    p_message_type TEXT,
    p_body_text TEXT,
    p_media_id TEXT,
    p_reply_to_message_id TEXT,
    p_preview TEXT,
    p_recipient_ids TEXT[],
    p_now_ms BIGINT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_rid TEXT;
BEGIN
    INSERT INTO public.messages (
        id, conversation_id, sender_id, client_message_id, message_type,
        body_text, media_id, reply_to_message_id, created_at_ms
    )
    VALUES (
        p_message_id, p_conversation_id, p_sender_id, p_client_message_id, p_message_type,
        p_body_text, p_media_id, p_reply_to_message_id, p_now_ms
    );

    UPDATE public.conversations
    SET last_message_id = p_message_id,
        last_message_preview = p_preview,
        last_message_type = p_message_type,
        last_message_sender_id = p_sender_id,
        last_message_at_ms = p_now_ms,
        updated_at_ms = p_now_ms
    WHERE id = p_conversation_id;

    FOREACH v_rid IN ARRAY p_recipient_ids LOOP
        INSERT INTO public.message_receipts (message_id, recipient_id)
        VALUES (p_message_id, v_rid)
        ON CONFLICT (message_id, recipient_id) DO NOTHING;

        UPDATE public.conversation_members
        SET unread_count = unread_count + 1
        WHERE conversation_id = p_conversation_id AND user_id = v_rid;
    END LOOP;
END;
$$;

-- 12. Mark Message Receipts Atomically
CREATE OR REPLACE FUNCTION public.mark_message_receipts(
    p_conversation_id TEXT,
    p_user_id TEXT,
    p_message_ids TEXT[],
    p_type TEXT,
    p_now_ms BIGINT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_last_msg_id TEXT;
BEGIN
    IF COALESCE(array_length(p_message_ids, 1), 0) = 0 THEN
        RETURN;
    END IF;

    IF p_type = 'delivered' THEN
        UPDATE public.message_receipts
        SET delivered_at_ms = COALESCE(delivered_at_ms, p_now_ms)
        WHERE recipient_id = p_user_id
          AND message_id = ANY(p_message_ids);
    ELSE
        UPDATE public.message_receipts
        SET delivered_at_ms = COALESCE(delivered_at_ms, p_now_ms),
            read_at_ms = COALESCE(read_at_ms, p_now_ms)
        WHERE recipient_id = p_user_id
          AND message_id = ANY(p_message_ids);

        v_last_msg_id := p_message_ids[array_length(p_message_ids, 1)];
        UPDATE public.conversation_members
        SET unread_count = 0,
            last_read_message_id = COALESCE(v_last_msg_id, last_read_message_id),
            last_read_at_ms = p_now_ms
        WHERE conversation_id = p_conversation_id AND user_id = p_user_id;
    END IF;
END;
$$;

-- 13. Delete Own Message for Everyone
CREATE OR REPLACE FUNCTION public.delete_own_message(
    p_message_id TEXT,
    p_conversation_id TEXT,
    p_media_id TEXT,
    p_now_ms BIGINT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    UPDATE public.messages
    SET deleted_for_all_at_ms = p_now_ms,
        body_text = NULL
    WHERE id = p_message_id;

    UPDATE public.conversations
    SET last_message_preview = CASE
        WHEN last_message_id = p_message_id THEN 'Message deleted'
        ELSE last_message_preview
    END
    WHERE id = p_conversation_id;

    IF p_media_id IS NOT NULL THEN
        UPDATE public.media_objects
        SET upload_status = 'deleted'
        WHERE id = p_media_id;
    END IF;
END;
$$;

-- 14. Initiate Call Transaction
CREATE OR REPLACE FUNCTION public.initiate_call_tx(
    p_call_id TEXT,
    p_conversation_id TEXT,
    p_caller_id TEXT,
    p_callee_id TEXT,
    p_call_type TEXT,
    p_now_ms BIGINT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    INSERT INTO public.calls (id, conversation_id, caller_id, call_type, status, started_at_ms)
    VALUES (p_call_id, p_conversation_id, p_caller_id, p_call_type, 'ringing', p_now_ms);

    INSERT INTO public.call_participants (call_id, user_id, state, joined_at_ms)
    VALUES (p_call_id, p_caller_id, 'joined', p_now_ms);

    INSERT INTO public.call_participants (call_id, user_id, state)
    VALUES (p_call_id, p_callee_id, 'ringing');
END;
$$;

-- 15. List Blocked Users for User
CREATE OR REPLACE FUNCTION public.list_blocked_users_for_user(p_user_id TEXT)
RETURNS TABLE (
    user_id TEXT,
    display_name TEXT,
    avatar_media_id TEXT,
    blocked_at_ms BIGINT
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT
        b.blocked_id AS user_id,
        p.display_name,
        p.avatar_media_id,
        b.created_at_ms AS blocked_at_ms
    FROM public.blocked_users b
    JOIN public.user_profiles p ON p.user_id = b.blocked_id
    WHERE b.blocker_id = p_user_id
    ORDER BY b.created_at_ms DESC;
$$;

-- 16. Verify Conversation Membership & No Block Between Two Users (WebSocket DO)
CREATE OR REPLACE FUNCTION public.check_conversation_pair(
    p_conversation_id TEXT,
    p_user_id TEXT,
    p_target_user_id TEXT
)
RETURNS BOOLEAN
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.conversation_members cm1
        JOIN public.conversation_members cm2
            ON cm1.conversation_id = cm2.conversation_id
        LEFT JOIN public.blocked_users b ON (
            (b.blocker_id = p_user_id AND b.blocked_id = p_target_user_id)
            OR (b.blocker_id = p_target_user_id AND b.blocked_id = p_user_id)
        )
        WHERE cm1.conversation_id = p_conversation_id
          AND cm1.user_id = p_user_id
          AND cm2.user_id = p_target_user_id
          AND b.blocker_id IS NULL
    );
$$;

-- 17. Owner Monitor Snapshot with Search & Server-side Pagination
CREATE OR REPLACE FUNCTION public.get_owner_activity_snapshot(
    p_search TEXT DEFAULT NULL,
    p_limit INTEGER DEFAULT 20,
    p_offset INTEGER DEFAULT 0,
    p_last_24h_cutoff BIGINT DEFAULT 0,
    p_now_ms BIGINT DEFAULT 0
)
RETURNS JSONB
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    WITH summary_stats AS (
        SELECT
            (SELECT COUNT(*)::INTEGER FROM public.users WHERE account_status = 'active') AS total_registered_users,
            (SELECT COUNT(*)::INTEGER FROM public.user_profiles p JOIN public.users u ON u.id = p.user_id WHERE u.account_status = 'active' AND p.is_online = TRUE) AS currently_online_users,
            (SELECT COUNT(*)::INTEGER FROM public.user_profiles p JOIN public.users u ON u.id = p.user_id WHERE u.account_status = 'active' AND p.last_seen_ms >= p_last_24h_cutoff) AS active_last_24h_users,
            (SELECT COUNT(*)::INTEGER FROM public.sessions s JOIN public.users u ON u.id = s.user_id WHERE u.account_status = 'active' AND s.revoked_at_ms IS NULL AND s.expires_at_ms > p_now_ms) AS active_sessions_count
    ),
    filtered_users AS (
        SELECT
            u.id AS user_id,
            p.display_name,
            u.country_iso2,
            u.phone_e164,
            p.is_online,
            p.last_seen_ms,
            u.created_at_ms,
            (SELECT d.device_name FROM public.devices d WHERE d.user_id = u.id ORDER BY d.last_active_ms DESC LIMIT 1) AS latest_device_name,
            (SELECT d.platform FROM public.devices d WHERE d.user_id = u.id ORDER BY d.last_active_ms DESC LIMIT 1) AS latest_platform,
            (SELECT d.app_version FROM public.devices d WHERE d.user_id = u.id ORDER BY d.last_active_ms DESC LIMIT 1) AS latest_app_version,
            (SELECT COUNT(*)::INTEGER FROM public.sessions s WHERE s.user_id = u.id AND s.revoked_at_ms IS NULL AND s.expires_at_ms > p_now_ms) AS active_session_count
        FROM public.users u
        JOIN public.user_profiles p ON p.user_id = u.id
        WHERE u.account_status = 'active'
          AND (
              p_search IS NULL
              OR BTRIM(p_search) = ''
              OR p.display_name ILIKE '%' || BTRIM(p_search) || '%'
              OR u.country_iso2 ILIKE '%' || BTRIM(p_search) || '%'
              OR u.phone_e164 ILIKE '%' || BTRIM(p_search) || '%'
          )
    ),
    total_cnt AS (
        SELECT COUNT(*)::INTEGER AS cnt FROM filtered_users
    ),
    paged_users AS (
        SELECT *
        FROM filtered_users
        ORDER BY is_online DESC, COALESCE(last_seen_ms, created_at_ms) DESC
        LIMIT GREATEST(1, LEAST(p_limit, 50))
        OFFSET GREATEST(0, p_offset)
    )
    SELECT jsonb_build_object(
        'summary', jsonb_build_object(
            'total_registered_users', (SELECT total_registered_users FROM summary_stats),
            'currently_online_users', (SELECT currently_online_users FROM summary_stats),
            'active_last_24h_users', (SELECT active_last_24h_users FROM summary_stats),
            'active_sessions_count', (SELECT active_sessions_count FROM summary_stats)
        ),
        'total_count', (SELECT cnt FROM total_cnt),
        'users', COALESCE((SELECT jsonb_agg(to_jsonb(paged_users.*)) FROM paged_users), '[]'::jsonb)
    );
$$;

-- 18. Delete User Account Atomically
CREATE OR REPLACE FUNCTION public.delete_user_account_tx(
    p_user_id TEXT,
    p_now_ms BIGINT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    UPDATE public.sessions SET revoked_at_ms = p_now_ms WHERE user_id = p_user_id;
    DELETE FROM public.notification_endpoints WHERE user_id = p_user_id;
    DELETE FROM public.blocked_users WHERE blocker_id = p_user_id OR blocked_id = p_user_id;
    DELETE FROM public.media_objects WHERE owner_user_id = p_user_id;
    DELETE FROM public.devices WHERE user_id = p_user_id;
    DELETE FROM public.user_profiles WHERE user_id = p_user_id;
    DELETE FROM public.users WHERE id = p_user_id;
END;
$$;
