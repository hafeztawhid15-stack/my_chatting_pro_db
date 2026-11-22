-- ============================================================================
-- MY CHATTING PRO — Private Chat & Separate 5-Digit Private PIN (Migration 0004)
-- Adds strictly isolated Private Chat conversations & hashed Private PIN storage.
-- Run this migration in Supabase SQL Editor on project kfgqysczabdkrrrlrpyn.
-- ============================================================================

-- 1. Separate Private PIN hash & lockout state on public.users
-- Never shares or overwrites the account login `pin_hash`.
ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS private_pin_hash TEXT,
    ADD COLUMN IF NOT EXISTS private_pin_algo TEXT NOT NULL DEFAULT 'pbkdf2_sha512_v1',
    ADD COLUMN IF NOT EXISTS private_pin_failed_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS private_pin_locked_until_ms BIGINT,
    ADD COLUMN IF NOT EXISTS private_pin_updated_at_ms BIGINT;

-- 2. Private Chat isolation flag on public.conversations
ALTER TABLE public.conversations
    ADD COLUMN IF NOT EXISTS is_private BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_conversations_is_private
    ON public.conversations (is_private);

-- 3. Update regular list_user_conversations to EXCLUDE private conversations
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
      AND c.is_private = FALSE
    ORDER BY COALESCE(c.last_message_at_ms, c.created_at_ms) DESC;
$$;

-- 4. List ONLY Private Chat conversations for an authenticated & PIN-verified user
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
      AND c.is_private = TRUE
    ORDER BY COALESCE(c.last_message_at_ms, c.created_at_ms) DESC;
$$;

-- 5. Update get_or_create_direct_conversation to support regular and private isolation
DROP FUNCTION IF EXISTS public.get_or_create_direct_conversation(TEXT, TEXT, TEXT, TEXT, BIGINT);

CREATE OR REPLACE FUNCTION public.get_or_create_direct_conversation(
    p_current_user_id TEXT,
    p_target_user_id TEXT,
    p_new_conv_id TEXT,
    p_direct_key TEXT,
    p_now_ms BIGINT,
    p_is_private BOOLEAN DEFAULT FALSE
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

    INSERT INTO public.conversations (id, type, direct_key, is_private, created_at_ms, updated_at_ms)
    VALUES (p_new_conv_id, 'direct', p_direct_key, COALESCE(p_is_private, FALSE), p_now_ms, p_now_ms)
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
