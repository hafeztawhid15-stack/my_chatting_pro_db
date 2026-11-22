-- ============================================================================
-- MY CHATTING PRO — Private Chat Move & Regular Chats Isolation (Migration 0006)
-- Owner / Developer: TAWHID SHAKH
-- Run this migration in Supabase SQL Editor on project kfgqysczabdkrrrlrpyn.
-- ============================================================================

-- 1. Track per-user Private Chat move state so User A can move a contact to
-- Private Chat (and hide them from User A's regular Chats list) without breaking
-- the peer's side or losing message history.
ALTER TABLE public.user_conversation_settings
    ADD COLUMN IF NOT EXISTS moved_to_private BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS moved_to_private_at_ms BIGINT;

CREATE INDEX IF NOT EXISTS idx_ucs_user_moved_private
    ON public.user_conversation_settings (user_id, moved_to_private, conversation_id);

-- 2. Exclude any conversation moved to Private Chat from regular list_user_conversations
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
        CASE
            WHEN ucs.cleared_at_ms IS NOT NULL AND COALESCE(c.last_message_at_ms, 0) <= ucs.cleared_at_ms THEN NULL
            ELSE c.last_message_id
        END AS last_message_id,
        CASE
            WHEN ucs.cleared_at_ms IS NOT NULL AND COALESCE(c.last_message_at_ms, 0) <= ucs.cleared_at_ms THEN NULL
            ELSE c.last_message_preview
        END AS last_message_preview,
        CASE
            WHEN ucs.cleared_at_ms IS NOT NULL AND COALESCE(c.last_message_at_ms, 0) <= ucs.cleared_at_ms THEN NULL
            ELSE c.last_message_type
        END AS last_message_type,
        CASE
            WHEN ucs.cleared_at_ms IS NOT NULL AND COALESCE(c.last_message_at_ms, 0) <= ucs.cleared_at_ms THEN NULL
            ELSE c.last_message_sender_id
        END AS last_message_sender_id,
        CASE
            WHEN ucs.cleared_at_ms IS NOT NULL AND COALESCE(c.last_message_at_ms, 0) <= ucs.cleared_at_ms THEN NULL
            ELSE c.last_message_at_ms
        END AS last_message_at_ms,
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
    WHERE my_cm.user_id = p_user_id
      AND c.is_private = FALSE
      AND COALESCE(ucs.moved_to_private, FALSE) = FALSE
      AND (ucs.deleted_at_ms IS NULL OR COALESCE(c.last_message_at_ms, 0) > ucs.deleted_at_ms)
    ORDER BY
        COALESCE(ucs.is_pinned, FALSE) DESC,
        COALESCE(ucs.pinned_at_ms, 0) DESC,
        COALESCE(c.last_message_at_ms, c.created_at_ms) DESC;
$$;

-- 3. Include both native private chats (c.is_private = TRUE) and per-user
-- moved chats (ucs.moved_to_private = TRUE) inside list_user_private_conversations.
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
        CASE
            WHEN ucs.cleared_at_ms IS NOT NULL AND COALESCE(c.last_message_at_ms, 0) <= ucs.cleared_at_ms THEN NULL
            ELSE c.last_message_id
        END AS last_message_id,
        CASE
            WHEN ucs.cleared_at_ms IS NOT NULL AND COALESCE(c.last_message_at_ms, 0) <= ucs.cleared_at_ms THEN NULL
            ELSE c.last_message_preview
        END AS last_message_preview,
        CASE
            WHEN ucs.cleared_at_ms IS NOT NULL AND COALESCE(c.last_message_at_ms, 0) <= ucs.cleared_at_ms THEN NULL
            ELSE c.last_message_type
        END AS last_message_type,
        CASE
            WHEN ucs.cleared_at_ms IS NOT NULL AND COALESCE(c.last_message_at_ms, 0) <= ucs.cleared_at_ms THEN NULL
            ELSE c.last_message_sender_id
        END AS last_message_sender_id,
        CASE
            WHEN ucs.cleared_at_ms IS NOT NULL AND COALESCE(c.last_message_at_ms, 0) <= ucs.cleared_at_ms THEN NULL
            ELSE c.last_message_at_ms
        END AS last_message_at_ms,
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
    WHERE my_cm.user_id = p_user_id
      AND (c.is_private = TRUE OR COALESCE(ucs.moved_to_private, FALSE) = TRUE)
      AND (ucs.deleted_at_ms IS NULL OR COALESCE(c.last_message_at_ms, 0) > ucs.deleted_at_ms)
    ORDER BY
        COALESCE(ucs.is_pinned, FALSE) DESC,
        COALESCE(ucs.pinned_at_ms, 0) DESC,
        COALESCE(c.last_message_at_ms, c.created_at_ms) DESC;
$$;
