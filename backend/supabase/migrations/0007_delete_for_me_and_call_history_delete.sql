-- ============================================================================
-- MY CHATTING PRO — Media Delete for Me & Per-User Call History Delete
-- Migration 0007
-- Owner / Developer: TAWHID SHAKH
-- Run this migration in Supabase SQL Editor on project kfgqysczabdkrrrlrpyn.
-- ============================================================================

-- 1. Per-user hidden messages ("Delete for Me")
CREATE TABLE IF NOT EXISTS public.message_hidden_for_users (
    user_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    message_id TEXT NOT NULL REFERENCES public.messages(id) ON DELETE CASCADE,
    hidden_at_ms BIGINT NOT NULL,
    PRIMARY KEY (user_id, message_id)
);

CREATE INDEX IF NOT EXISTS idx_message_hidden_user_msg
    ON public.message_hidden_for_users (user_id, message_id);

ALTER TABLE public.message_hidden_for_users ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.message_hidden_for_users FROM anon, authenticated;
DROP POLICY IF EXISTS deny_all_message_hidden_for_users ON public.message_hidden_for_users;
CREATE POLICY deny_all_message_hidden_for_users
    ON public.message_hidden_for_users
    FOR ALL
    TO anon, authenticated
    USING (FALSE)
    WITH CHECK (FALSE);

-- 2. Per-user soft deletion timestamp on call_participants (never deletes peer's history)
ALTER TABLE public.call_participants
    ADD COLUMN IF NOT EXISTS deleted_at_ms BIGINT;

CREATE INDEX IF NOT EXISTS idx_call_participants_user_deleted
    ON public.call_participants (user_id, deleted_at_ms, call_id);

-- 3. Hide a single message only for the requesting user
CREATE OR REPLACE FUNCTION public.hide_message_for_user(
    p_user_id TEXT,
    p_message_id TEXT,
    p_now_ms BIGINT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    INSERT INTO public.message_hidden_for_users (user_id, message_id, hidden_at_ms)
    VALUES (p_user_id, p_message_id, p_now_ms)
    ON CONFLICT (user_id, message_id) DO NOTHING;
END;
$$;

-- 4. Update delete_own_message to also clear media_id reference on messages
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
        body_text = NULL,
        media_id = NULL
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

-- 5. Update list_conversation_messages to exclude per-user hidden messages ("Delete for Me")
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
    WITH caller_cutoff AS (
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
    WHERE m.conversation_id = p_conversation_id
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

-- 6. Update list_user_conversations so hidden "Delete for Me" messages update caller's preview cleanly
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

-- 7. Update list_user_private_conversations identically
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

-- 8. Update list_user_calls to exclude calls soft-deleted by this user (peer row untouched)
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
    LEFT JOIN public.user_profiles pr
        ON pr.user_id = other_cp.user_id
    WHERE my_cp.deleted_at_ms IS NULL
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
