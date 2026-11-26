-- ============================================================================
-- MY CHATTING PRO — 20-Day Automatic Retention & Auto-Delete System
-- Migration 0008
-- Owner / Developer: TAWHID SHAKH
-- Run this migration in Supabase SQL Editor on project kfgqysczabdkrrrlrpyn.
-- Automatically deletes messages, media (photos/videos/voice/audio/files),
-- and call history older than 20 days (1,728,000,000 ms).
-- User accounts, profiles, profile avatars, and conversations are preserved.
-- ============================================================================

-- 1. Performance indexes for scheduled 20-day cleanup
CREATE INDEX IF NOT EXISTS idx_messages_created_at_ms
    ON public.messages (created_at_ms);

CREATE INDEX IF NOT EXISTS idx_media_objects_nonprofile_created
    ON public.media_objects (created_at_ms)
    WHERE category <> 'profile';

CREATE INDEX IF NOT EXISTS idx_calls_started_at_ms
    ON public.calls (started_at_ms);

-- 2. Scheduled batch purge RPC invoked by Cloudflare Worker Cron & throttled triggers
CREATE OR REPLACE FUNCTION public.purge_expired_retention_data(
    p_cutoff_ms BIGINT,
    p_purged_media_ids TEXT[] DEFAULT ARRAY[]::TEXT[]
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_deleted_messages INTEGER := 0;
    v_deleted_calls INTEGER := 0;
    v_deleted_media INTEGER := 0;
    v_affected_convs TEXT[];
BEGIN
    -- Collect conversations that have expired messages or stale last_message_at_ms
    SELECT ARRAY(
        SELECT DISTINCT cid FROM (
            SELECT m.conversation_id AS cid
            FROM public.messages m
            WHERE m.created_at_ms < p_cutoff_ms
            UNION
            SELECT c.id AS cid
            FROM public.conversations c
            WHERE c.last_message_at_ms IS NOT NULL
              AND c.last_message_at_ms < p_cutoff_ms
        ) t
    ) INTO v_affected_convs;

    -- Delete expired messages (cascades message_receipts & message_hidden_for_users)
    WITH deleted_msgs AS (
        DELETE FROM public.messages
        WHERE created_at_ms < p_cutoff_ms
        RETURNING id
    )
    SELECT COUNT(*) INTO v_deleted_messages FROM deleted_msgs;

    -- Delete expired call records (cascades call_participants)
    WITH deleted_calls AS (
        DELETE FROM public.calls
        WHERE started_at_ms < p_cutoff_ms
        RETURNING id
    )
    SELECT COUNT(*) INTO v_deleted_calls FROM deleted_calls;

    -- Delete media_objects rows whose storage files were removed by Worker, plus old deleted placeholders
    WITH deleted_media AS (
        DELETE FROM public.media_objects
        WHERE category <> 'profile'
          AND (
              id = ANY(COALESCE(p_purged_media_ids, ARRAY[]::TEXT[]))
              OR (upload_status = 'deleted' AND created_at_ms < p_cutoff_ms)
          )
        RETURNING id
    )
    SELECT COUNT(*) INTO v_deleted_media FROM deleted_media;

    -- Recompute conversation last_message summaries from remaining non-expired messages
    IF v_affected_convs IS NOT NULL AND array_length(v_affected_convs, 1) > 0 THEN
        UPDATE public.conversations c
        SET
            last_message_id = latest.id,
            last_message_preview = latest.preview,
            last_message_type = latest.message_type,
            last_message_sender_id = latest.sender_id,
            last_message_at_ms = latest.created_at_ms
        FROM (
            SELECT
                conv.id AS conv_id,
                m.id,
                CASE
                    WHEN m.id IS NULL THEN NULL
                    WHEN m.deleted_for_all_at_ms IS NOT NULL THEN 'Message deleted'
                    WHEN m.message_type = 'image' THEN 'Photo'
                    WHEN m.message_type = 'video' THEN 'Video'
                    WHEN m.message_type = 'voice' THEN 'Voice message'
                    WHEN m.message_type = 'audio' THEN 'Audio file'
                    WHEN m.message_type = 'file' THEN 'Document / File'
                    WHEN m.message_type = 'call_log' THEN COALESCE(m.body_text, 'Call')
                    ELSE LEFT(COALESCE(m.body_text, ''), 80)
                END AS preview,
                m.message_type,
                m.sender_id,
                m.created_at_ms
            FROM unnest(v_affected_convs) AS conv(id)
            LEFT JOIN LATERAL (
                SELECT msg.id, msg.deleted_for_all_at_ms, msg.message_type, msg.body_text, msg.sender_id, msg.created_at_ms
                FROM public.messages msg
                WHERE msg.conversation_id = conv.id
                ORDER BY msg.created_at_ms DESC, msg.id DESC
                LIMIT 1
            ) m ON TRUE
        ) latest
        WHERE c.id = latest.conv_id;
    END IF;

    -- Opportunistic housekeeping for expired/revoked sessions and stale rate limit buckets
    DELETE FROM public.sessions
    WHERE expires_at_ms < p_cutoff_ms
       OR (revoked_at_ms IS NOT NULL AND revoked_at_ms < p_cutoff_ms);

    DELETE FROM public.rate_limits
    WHERE window_start_ms < p_cutoff_ms;

    RETURN jsonb_build_object(
        'deletedMessages', v_deleted_messages,
        'deletedCalls', v_deleted_calls,
        'deletedMediaRows', v_deleted_media
    );
END;
$$;

-- 3. Enforce 20-day cutoff immediately on read queries (messages, chats, private chats, calls)
DROP FUNCTION IF EXISTS public.list_conversation_messages(TEXT, BIGINT, TEXT, INTEGER, TEXT);

CREATE OR REPLACE FUNCTION public.list_conversation_messages(
    p_conversation_id TEXT,
    p_before_ts BIGINT DEFAULT NULL,
    p_before_id TEXT DEFAULT NULL,
    p_limit INTEGER DEFAULT 41,
    p_user_id TEXT DEFAULT NULL
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
    WITH retention AS (
        SELECT ((EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT - 1728000000) AS cutoff_ms
    ),
    caller_cutoff AS (
        SELECT GREATEST(COALESCE(cleared_at_ms, 0), COALESCE(deleted_at_ms, 0)) AS cutoff_ms
        FROM public.user_conversation_settings
        WHERE user_id = p_user_id AND conversation_id = p_conversation_id
        LIMIT 1
    )
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
    CROSS JOIN retention ret
    WHERE m.conversation_id = p_conversation_id
      AND m.created_at_ms >= ret.cutoff_ms
      AND (
          p_user_id IS NULL
          OR COALESCE((SELECT cutoff_ms FROM caller_cutoff), 0) = 0
          OR m.created_at_ms > (SELECT cutoff_ms FROM caller_cutoff)
      )
      AND (
          p_user_id IS NULL
          OR NOT EXISTS (
              SELECT 1
              FROM public.message_hidden_for_users mhu
              WHERE mhu.user_id = p_user_id
                AND mhu.message_id = m.id
          )
      )
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

DROP FUNCTION IF EXISTS public.list_user_conversations(TEXT);

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
    blocked_me BOOLEAN,
    is_pinned BOOLEAN,
    pinned_at_ms BIGINT,
    is_archived BOOLEAN,
    archived_at_ms BIGINT,
    muted_until_ms BIGINT,
    custom_sound_uri TEXT,
    custom_sound_label TEXT,
    marked_unread BOOLEAN
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    WITH retention AS (
        SELECT ((EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT - 1728000000) AS cutoff_ms
    )
    SELECT
        c.id,
        c.type,
        vm.visible_last_message_id AS last_message_id,
        vm.visible_last_message_preview AS last_message_preview,
        vm.visible_last_message_type AS last_message_type,
        vm.visible_last_message_sender_id AS last_message_sender_id,
        vm.visible_last_message_at_ms AS last_message_at_ms,
        CASE
            WHEN COALESCE(ucs.marked_unread, FALSE) AND my_cm.unread_count = 0 THEN 1
            ELSE my_cm.unread_count
        END AS unread_count,
        c.updated_at_ms,
        peer_cm.user_id AS peer_user_id,
        p.display_name AS peer_display_name,
        p.avatar_media_id AS peer_avatar_media_id,
        p.about_text AS peer_about_text,
        p.is_online AS peer_is_online,
        p.last_seen_ms AS peer_last_seen_ms,
        (b1.blocker_id IS NOT NULL) AS blocked_by_me,
        (b2.blocker_id IS NOT NULL) AS blocked_me,
        COALESCE(ucs.is_pinned, FALSE) AS is_pinned,
        ucs.pinned_at_ms,
        COALESCE(ucs.is_archived, FALSE) AS is_archived,
        ucs.archived_at_ms,
        ucs.muted_until_ms,
        ucs.custom_sound_uri,
        ucs.custom_sound_label,
        COALESCE(ucs.marked_unread, FALSE) AS marked_unread
    FROM public.conversation_members my_cm
    JOIN public.conversations c ON c.id = my_cm.conversation_id
    JOIN public.conversation_members peer_cm
        ON peer_cm.conversation_id = c.id AND peer_cm.user_id <> my_cm.user_id
    JOIN public.users pu
        ON pu.id = peer_cm.user_id AND pu.account_status = 'active'
    JOIN public.user_profiles p ON p.user_id = peer_cm.user_id
    CROSS JOIN retention ret
    LEFT JOIN public.user_conversation_settings ucs
        ON ucs.user_id = p_user_id AND ucs.conversation_id = c.id
    LEFT JOIN public.blocked_users b1
        ON b1.blocker_id = p_user_id AND b1.blocked_id = peer_cm.user_id
    LEFT JOIN public.blocked_users b2
        ON b2.blocker_id = peer_cm.user_id AND b2.blocked_id = p_user_id
    LEFT JOIN LATERAL (
        SELECT
            m.id AS visible_last_message_id,
            CASE
                WHEN m.deleted_for_all_at_ms IS NOT NULL THEN 'Message deleted'
                WHEN m.message_type = 'image' THEN 'Photo'
                WHEN m.message_type = 'video' THEN 'Video'
                WHEN m.message_type = 'voice' THEN 'Voice message'
                WHEN m.message_type = 'audio' THEN 'Audio file'
                WHEN m.message_type = 'file' THEN 'Document / File'
                WHEN m.message_type = 'call_log' THEN COALESCE(m.body_text, 'Call')
                ELSE LEFT(COALESCE(m.body_text, ''), 80)
            END AS visible_last_message_preview,
            m.message_type AS visible_last_message_type,
            m.sender_id AS visible_last_message_sender_id,
            m.created_at_ms AS visible_last_message_at_ms
        FROM public.messages m
        WHERE m.conversation_id = c.id
          AND m.created_at_ms >= ret.cutoff_ms
          AND m.created_at_ms > GREATEST(COALESCE(ucs.cleared_at_ms, 0), COALESCE(ucs.deleted_at_ms, 0))
          AND NOT EXISTS (
              SELECT 1
              FROM public.message_hidden_for_users mhu
              WHERE mhu.user_id = p_user_id
                AND mhu.message_id = m.id
          )
        ORDER BY m.created_at_ms DESC, m.id DESC
        LIMIT 1
    ) vm ON TRUE
    WHERE my_cm.user_id = p_user_id
      AND c.is_private = FALSE
      AND COALESCE(ucs.moved_to_private, FALSE) = FALSE
      AND (ucs.deleted_at_ms IS NULL OR COALESCE(c.last_message_at_ms, 0) > ucs.deleted_at_ms)
    ORDER BY
        COALESCE(ucs.is_pinned, FALSE) DESC,
        COALESCE(ucs.pinned_at_ms, 0) DESC,
        COALESCE(vm.visible_last_message_at_ms, c.last_message_at_ms, c.created_at_ms) DESC;
$$;

DROP FUNCTION IF EXISTS public.list_user_private_conversations(TEXT);

CREATE OR REPLACE FUNCTION public.list_user_private_conversations(p_user_id TEXT)
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
    blocked_me BOOLEAN,
    is_pinned BOOLEAN,
    pinned_at_ms BIGINT,
    is_archived BOOLEAN,
    archived_at_ms BIGINT,
    muted_until_ms BIGINT,
    custom_sound_uri TEXT,
    custom_sound_label TEXT,
    marked_unread BOOLEAN
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    WITH retention AS (
        SELECT ((EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT - 1728000000) AS cutoff_ms
    )
    SELECT
        c.id,
        c.type,
        vm.visible_last_message_id AS last_message_id,
        vm.visible_last_message_preview AS last_message_preview,
        vm.visible_last_message_type AS last_message_type,
        vm.visible_last_message_sender_id AS last_message_sender_id,
        vm.visible_last_message_at_ms AS last_message_at_ms,
        CASE
            WHEN COALESCE(ucs.marked_unread, FALSE) AND my_cm.unread_count = 0 THEN 1
            ELSE my_cm.unread_count
        END AS unread_count,
        c.updated_at_ms,
        peer_cm.user_id AS peer_user_id,
        p.display_name AS peer_display_name,
        p.avatar_media_id AS peer_avatar_media_id,
        p.about_text AS peer_about_text,
        p.is_online AS peer_is_online,
        p.last_seen_ms AS peer_last_seen_ms,
        (b1.blocker_id IS NOT NULL) AS blocked_by_me,
        (b2.blocker_id IS NOT NULL) AS blocked_me,
        COALESCE(ucs.is_pinned, FALSE) AS is_pinned,
        ucs.pinned_at_ms,
        COALESCE(ucs.is_archived, FALSE) AS is_archived,
        ucs.archived_at_ms,
        ucs.muted_until_ms,
        ucs.custom_sound_uri,
        ucs.custom_sound_label,
        COALESCE(ucs.marked_unread, FALSE) AS marked_unread
    FROM public.conversation_members my_cm
    JOIN public.conversations c ON c.id = my_cm.conversation_id
    JOIN public.conversation_members peer_cm
        ON peer_cm.conversation_id = c.id AND peer_cm.user_id <> my_cm.user_id
    JOIN public.users pu
        ON pu.id = peer_cm.user_id AND pu.account_status = 'active'
    JOIN public.user_profiles p ON p.user_id = peer_cm.user_id
    CROSS JOIN retention ret
    LEFT JOIN public.user_conversation_settings ucs
        ON ucs.user_id = p_user_id AND ucs.conversation_id = c.id
    LEFT JOIN public.blocked_users b1
        ON b1.blocker_id = p_user_id AND b1.blocked_id = peer_cm.user_id
    LEFT JOIN public.blocked_users b2
        ON b2.blocker_id = peer_cm.user_id AND b2.blocked_id = p_user_id
    LEFT JOIN LATERAL (
        SELECT
            m.id AS visible_last_message_id,
            CASE
                WHEN m.deleted_for_all_at_ms IS NOT NULL THEN 'Message deleted'
                WHEN m.message_type = 'image' THEN 'Photo'
                WHEN m.message_type = 'video' THEN 'Video'
                WHEN m.message_type = 'voice' THEN 'Voice message'
                WHEN m.message_type = 'audio' THEN 'Audio file'
                WHEN m.message_type = 'file' THEN 'Document / File'
                WHEN m.message_type = 'call_log' THEN COALESCE(m.body_text, 'Call')
                ELSE LEFT(COALESCE(m.body_text, ''), 80)
            END AS visible_last_message_preview,
            m.message_type AS visible_last_message_type,
            m.sender_id AS visible_last_message_sender_id,
            m.created_at_ms AS visible_last_message_at_ms
        FROM public.messages m
        WHERE m.conversation_id = c.id
          AND m.created_at_ms >= ret.cutoff_ms
          AND m.created_at_ms > GREATEST(COALESCE(ucs.cleared_at_ms, 0), COALESCE(ucs.deleted_at_ms, 0))
          AND NOT EXISTS (
              SELECT 1
              FROM public.message_hidden_for_users mhu
              WHERE mhu.user_id = p_user_id
                AND mhu.message_id = m.id
          )
        ORDER BY m.created_at_ms DESC, m.id DESC
        LIMIT 1
    ) vm ON TRUE
    WHERE my_cm.user_id = p_user_id
      AND (c.is_private = TRUE OR COALESCE(ucs.moved_to_private, FALSE) = TRUE)
      AND (ucs.deleted_at_ms IS NULL OR COALESCE(c.last_message_at_ms, 0) > ucs.deleted_at_ms)
    ORDER BY
        COALESCE(ucs.is_pinned, FALSE) DESC,
        COALESCE(ucs.pinned_at_ms, 0) DESC,
        COALESCE(vm.visible_last_message_at_ms, c.last_message_at_ms, c.created_at_ms) DESC;
$$;

CREATE OR REPLACE FUNCTION public.list_user_calls(
    p_user_id TEXT,
    p_filter TEXT DEFAULT 'all',
    p_limit INTEGER DEFAULT 60
)
RETURNS TABLE (
    call_id TEXT,
    conversation_id TEXT,
    caller_id TEXT,
    peer_user_id TEXT,
    peer_display_name TEXT,
    peer_avatar_media_id TEXT,
    call_type TEXT,
    status TEXT,
    direction TEXT,
    started_at_ms BIGINT,
    answered_at_ms BIGINT,
    ended_at_ms BIGINT,
    duration_seconds INTEGER
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    WITH retention AS (
        SELECT ((EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT - 1728000000) AS cutoff_ms
    )
    SELECT
        ca.id AS call_id,
        ca.conversation_id,
        ca.caller_id,
        other_cp.user_id AS peer_user_id,
        COALESCE(pr.display_name, 'Deleted User') AS peer_display_name,
        pr.avatar_media_id AS peer_avatar_media_id,
        ca.call_type,
        ca.status,
        CASE WHEN ca.caller_id = p_user_id THEN 'outgoing' ELSE 'incoming' END AS direction,
        ca.started_at_ms,
        ca.answered_at_ms,
        ca.ended_at_ms,
        ca.duration_seconds
    FROM public.calls ca
    JOIN public.call_participants my_cp
        ON my_cp.call_id = ca.id AND my_cp.user_id = p_user_id
    JOIN public.call_participants other_cp
        ON other_cp.call_id = ca.id AND other_cp.user_id <> p_user_id
    JOIN public.conversations conv
        ON conv.id = ca.conversation_id AND conv.is_private = FALSE
    CROSS JOIN retention ret
    LEFT JOIN public.user_profiles pr
        ON pr.user_id = other_cp.user_id
    WHERE my_cp.deleted_at_ms IS NULL
      AND ca.started_at_ms >= ret.cutoff_ms
      AND (
        p_filter = 'all'
        OR (p_filter = 'outgoing' AND ca.caller_id = p_user_id)
        OR (p_filter = 'incoming' AND ca.caller_id <> p_user_id)
        OR (p_filter = 'missed' AND ca.status = 'missed')
        OR (p_filter = 'rejected' AND ca.status = 'rejected')
    )
    ORDER BY ca.started_at_ms DESC
    LIMIT GREATEST(1, LEAST(COALESCE(p_limit, 60), 120));
$$;
