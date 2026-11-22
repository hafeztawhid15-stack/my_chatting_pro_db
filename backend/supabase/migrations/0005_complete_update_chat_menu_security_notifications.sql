-- ============================================================================
-- MY CHATTING PRO — Complete Update: Security, Chat Preferences, Call History,
-- Duplicate Report Prevention & Notifications (Migration 0005)
-- Owner / Developer: TAWHID SHAKH
-- Run this migration in Supabase SQL Editor on project kfgqysczabdkrrrlrpyn.
-- ============================================================================

-- 1. Account Login PIN recovery & update metadata on public.users
-- Never uses OTP and never weakens PIN hashing (PBKDF2-HMAC-SHA512 + salt + pepper).
ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS recovery_code_hash TEXT,
    ADD COLUMN IF NOT EXISTS recovery_code_algo TEXT NOT NULL DEFAULT 'pbkdf2_sha512_v1',
    ADD COLUMN IF NOT EXISTS recovery_code_updated_at_ms BIGINT,
    ADD COLUMN IF NOT EXISTS recovery_failed_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS recovery_locked_until_ms BIGINT,
    ADD COLUMN IF NOT EXISTS pin_updated_at_ms BIGINT;

-- 2. Per-user conversation preferences & per-user clear/delete isolation
CREATE TABLE IF NOT EXISTS public.user_conversation_settings (
    user_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    conversation_id TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    is_pinned BOOLEAN NOT NULL DEFAULT FALSE,
    pinned_at_ms BIGINT,
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    archived_at_ms BIGINT,
    muted_until_ms BIGINT, -- NULL = unmuted, -1 = muted indefinitely, >0 = epoch ms expiry
    custom_sound_uri TEXT,
    custom_sound_label TEXT,
    marked_unread BOOLEAN NOT NULL DEFAULT FALSE,
    cleared_at_ms BIGINT,
    deleted_at_ms BIGINT,
    updated_at_ms BIGINT NOT NULL,
    PRIMARY KEY (user_id, conversation_id)
);

CREATE INDEX IF NOT EXISTS idx_ucs_user_pin_archive
    ON public.user_conversation_settings (user_id, is_pinned DESC, is_archived, updated_at_ms DESC);

ALTER TABLE public.user_conversation_settings ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.user_conversation_settings FROM anon, authenticated;
DROP POLICY IF EXISTS deny_all_user_conv_settings ON public.user_conversation_settings;
CREATE POLICY deny_all_user_conv_settings
    ON public.user_conversation_settings
    FOR ALL
    TO anon, authenticated
    USING (FALSE)
    WITH CHECK (FALSE);

-- 3. Deduplicate any existing duplicate open reports, then enforce duplicate report prevention & call history indexes
DELETE FROM public.reports r
USING (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY reporter_id, target_user_id, reason
               ORDER BY created_at_ms ASC, id ASC
           ) AS rn
    FROM public.reports
    WHERE status = 'open'
) dup
WHERE r.id = dup.id
  AND dup.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS idx_reports_open_dedup
    ON public.reports (reporter_id, target_user_id, reason)
    WHERE status = 'open';

CREATE INDEX IF NOT EXISTS idx_calls_started_at
    ON public.calls (started_at_ms DESC);

CREATE INDEX IF NOT EXISTS idx_call_participants_user
    ON public.call_participants (user_id, call_id);

-- 4. Update list_user_conversations to include per-user preferences, pin ordering,
-- per-user delete filtering, and per-user cleared message preview masking.
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
      AND (ucs.deleted_at_ms IS NULL OR COALESCE(c.last_message_at_ms, 0) > ucs.deleted_at_ms)
    ORDER BY
        COALESCE(ucs.is_pinned, FALSE) DESC,
        COALESCE(ucs.pinned_at_ms, 0) DESC,
        COALESCE(c.last_message_at_ms, c.created_at_ms) DESC;
$$;

-- 5. Update list_user_private_conversations identically (strictly isolated is_private = TRUE)
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
      AND c.is_private = TRUE
      AND (ucs.deleted_at_ms IS NULL OR COALESCE(c.last_message_at_ms, 0) > ucs.deleted_at_ms)
    ORDER BY
        COALESCE(ucs.is_pinned, FALSE) DESC,
        COALESCE(ucs.pinned_at_ms, 0) DESC,
        COALESCE(c.last_message_at_ms, c.created_at_ms) DESC;
$$;

-- 6. Update list_conversation_messages to respect caller's per-user cleared_at_ms / deleted_at_ms
DROP FUNCTION IF EXISTS public.list_conversation_messages(TEXT, BIGINT, TEXT, INTEGER);

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

-- 7. Update get_or_create_direct_conversation so re-opening clears caller's deleted_at_ms
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
    IF v_conv_id IS NULL THEN
        INSERT INTO public.conversations (id, type, direct_key, is_private, created_at_ms, updated_at_ms)
        VALUES (p_new_conv_id, 'direct', p_direct_key, COALESCE(p_is_private, FALSE), p_now_ms, p_now_ms)
        ON CONFLICT (direct_key) DO NOTHING;

        SELECT id INTO v_conv_id FROM public.conversations WHERE direct_key = p_direct_key;
    END IF;

    INSERT INTO public.conversation_members (conversation_id, user_id, role, unread_count, joined_at_ms)
    VALUES (v_conv_id, p_current_user_id, 'member', 0, p_now_ms)
    ON CONFLICT (conversation_id, user_id) DO NOTHING;

    INSERT INTO public.conversation_members (conversation_id, user_id, role, unread_count, joined_at_ms)
    VALUES (v_conv_id, p_target_user_id, 'member', 0, p_now_ms)
    ON CONFLICT (conversation_id, user_id) DO NOTHING;

    UPDATE public.user_conversation_settings
    SET deleted_at_ms = NULL,
        updated_at_ms = p_now_ms
    WHERE user_id = p_current_user_id AND conversation_id = v_conv_id AND deleted_at_ms IS NOT NULL;

    RETURN v_conv_id;
END;
$$;

-- 8. Update send_chat_message to un-delete conversations on new activity & auto-unarchive unmuted chats
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

    -- Restore sender's conversation visibility if they previously soft-deleted it
    UPDATE public.user_conversation_settings
    SET deleted_at_ms = NULL,
        updated_at_ms = p_now_ms
    WHERE user_id = p_sender_id AND conversation_id = p_conversation_id AND deleted_at_ms IS NOT NULL;

    FOREACH v_rid IN ARRAY p_recipient_ids LOOP
        INSERT INTO public.message_receipts (message_id, recipient_id)
        VALUES (p_message_id, v_rid)
        ON CONFLICT (message_id, recipient_id) DO NOTHING;

        UPDATE public.conversation_members
        SET unread_count = unread_count + 1
        WHERE conversation_id = p_conversation_id AND user_id = v_rid;

        -- Restore conversation in recipient list; auto-unarchive unless currently muted
        UPDATE public.user_conversation_settings
        SET deleted_at_ms = NULL,
            is_archived = CASE
                WHEN is_archived AND (muted_until_ms IS NULL OR (muted_until_ms > 0 AND muted_until_ms <= p_now_ms))
                    THEN FALSE
                ELSE is_archived
            END,
            archived_at_ms = CASE
                WHEN is_archived AND (muted_until_ms IS NULL OR (muted_until_ms > 0 AND muted_until_ms <= p_now_ms))
                    THEN NULL
                ELSE archived_at_ms
            END,
            updated_at_ms = p_now_ms
        WHERE user_id = v_rid AND conversation_id = p_conversation_id;
    END LOOP;
END;
$$;

-- 9. Update mark_message_receipts to also clear marked_unread on read
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
    IF p_type = 'read' THEN
        UPDATE public.user_conversation_settings
        SET marked_unread = FALSE,
            updated_at_ms = p_now_ms
        WHERE user_id = p_user_id
          AND conversation_id = p_conversation_id
          AND marked_unread = TRUE;

        UPDATE public.conversation_members
        SET unread_count = 0,
            last_read_at_ms = p_now_ms
        WHERE conversation_id = p_conversation_id AND user_id = p_user_id;
    END IF;

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

-- 10. Persistent Call History RPC (filter: all | incoming | outgoing | missed | rejected)
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
    WHERE (
        p_filter = 'all'
        OR (p_filter = 'outgoing' AND ca.caller_id = p_user_id)
        OR (p_filter = 'incoming' AND ca.caller_id <> p_user_id)
        OR (p_filter = 'missed' AND ca.status = 'missed')
        OR (p_filter = 'rejected' AND ca.status = 'rejected')
    )
    ORDER BY ca.started_at_ms DESC
    LIMIT GREATEST(1, LEAST(COALESCE(p_limit, 60), 120));
$$;

-- 11. Strengthen Permanent Account Deletion (clean up all user data & orphaned direct conversations)
CREATE OR REPLACE FUNCTION public.delete_user_account_tx(
    p_user_id TEXT,
    p_now_ms BIGINT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_orphan_conv_ids TEXT[];
BEGIN
    SELECT ARRAY_AGG(conversation_id)
    INTO v_orphan_conv_ids
    FROM public.conversation_members
    WHERE user_id = p_user_id;

    UPDATE public.sessions SET revoked_at_ms = p_now_ms WHERE user_id = p_user_id;
    DELETE FROM public.notification_endpoints WHERE user_id = p_user_id;
    DELETE FROM public.user_conversation_settings WHERE user_id = p_user_id;
    DELETE FROM public.blocked_users WHERE blocker_id = p_user_id OR blocked_id = p_user_id;
    DELETE FROM public.reports WHERE reporter_id = p_user_id;
    DELETE FROM public.media_objects WHERE owner_user_id = p_user_id;
    DELETE FROM public.devices WHERE user_id = p_user_id;
    DELETE FROM public.user_profiles WHERE user_id = p_user_id;
    DELETE FROM public.users WHERE id = p_user_id;

    -- Clean up any direct conversation that now has zero or 1 active member left
    IF v_orphan_conv_ids IS NOT NULL THEN
        DELETE FROM public.conversations c
        WHERE c.id = ANY(v_orphan_conv_ids)
          AND c.type = 'direct'
          AND (SELECT COUNT(*) FROM public.conversation_members cm WHERE cm.conversation_id = c.id) < 2;
    END IF;
END;
$$;
