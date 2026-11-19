-- ============================================================================
-- MY CHATTING PRO — Supabase PostgreSQL Primary Schema (Migration 0001)
-- Owner / Developer: TAWHID SHAKH
-- Backend Architecture: Supabase PostgreSQL + Private Storage + Cloudflare Workers & Durable Objects
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. Core user accounts (Phone E.164 + 5-digit PBKDF2-HMAC-SHA512 PIN hash; NO OTP)
CREATE TABLE IF NOT EXISTS public.users (
    id TEXT PRIMARY KEY,
    phone_e164 TEXT NOT NULL UNIQUE,
    country_iso2 TEXT NOT NULL,
    calling_code TEXT NOT NULL,
    pin_hash TEXT NOT NULL,
    pin_algo TEXT NOT NULL DEFAULT 'pbkdf2_sha512_v1',
    role TEXT NOT NULL DEFAULT 'user' CHECK (role IN ('user', 'owner')),
    failed_login_count INTEGER NOT NULL DEFAULT 0,
    locked_until_ms BIGINT,
    last_failed_login_ms BIGINT,
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    account_status TEXT NOT NULL DEFAULT 'active' CHECK (account_status IN ('active', 'deleted')),
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_users_phone_active ON public.users (phone_e164, account_status);
CREATE INDEX IF NOT EXISTS idx_users_role_active ON public.users (role, account_status);

-- 2. Public/shared profile & live presence
CREATE TABLE IF NOT EXISTS public.user_profiles (
    user_id TEXT PRIMARY KEY REFERENCES public.users(id) ON DELETE CASCADE,
    display_name TEXT NOT NULL,
    about_text TEXT NOT NULL DEFAULT 'Available on MY CHATTING PRO',
    avatar_media_id TEXT,
    last_seen_ms BIGINT,
    is_online BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at_ms BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_profiles_name_lower ON public.user_profiles (LOWER(display_name));
CREATE INDEX IF NOT EXISTS idx_user_profiles_online_seen ON public.user_profiles (is_online DESC, last_seen_ms DESC);

-- 3. Registered client devices
CREATE TABLE IF NOT EXISTS public.devices (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    device_name TEXT NOT NULL,
    platform TEXT NOT NULL DEFAULT 'android',
    app_version TEXT NOT NULL,
    last_active_ms BIGINT NOT NULL,
    created_at_ms BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_devices_user_active ON public.devices (user_id, last_active_ms DESC);

-- 4. Rotating refresh token sessions
CREATE TABLE IF NOT EXISTS public.sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
    refresh_token_hash TEXT NOT NULL UNIQUE,
    expires_at_ms BIGINT NOT NULL,
    revoked_at_ms BIGINT,
    last_used_at_ms BIGINT NOT NULL,
    created_at_ms BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sessions_user_active ON public.sessions (user_id, revoked_at_ms, expires_at_ms);
CREATE INDEX IF NOT EXISTS idx_sessions_refresh_hash ON public.sessions (refresh_token_hash);

-- 5. Conversations (Direct 1-to-1 or reserved Group)
CREATE TABLE IF NOT EXISTS public.conversations (
    id TEXT PRIMARY KEY,
    type TEXT NOT NULL CHECK (type IN ('direct', 'group')),
    direct_key TEXT UNIQUE,
    title TEXT,
    last_message_id TEXT,
    last_message_preview TEXT,
    last_message_type TEXT,
    last_message_sender_id TEXT,
    last_message_at_ms BIGINT,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_conversations_updated ON public.conversations (last_message_at_ms DESC NULLS LAST, updated_at_ms DESC);

-- 6. Conversation membership & per-user unread counters
CREATE TABLE IF NOT EXISTS public.conversation_members (
    conversation_id TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    role TEXT NOT NULL DEFAULT 'member' CHECK (role IN ('owner', 'admin', 'member')),
    unread_count INTEGER NOT NULL DEFAULT 0,
    last_read_message_id TEXT,
    last_read_at_ms BIGINT,
    joined_at_ms BIGINT NOT NULL,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_conv_members_user ON public.conversation_members (user_id, conversation_id);

-- 7. Supabase Storage private media metadata (files reside in private Supabase Storage bucket)
CREATE TABLE IF NOT EXISTS public.media_objects (
    id TEXT PRIMARY KEY,
    owner_user_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    conversation_id TEXT REFERENCES public.conversations(id) ON DELETE CASCADE,
    category TEXT NOT NULL CHECK (category IN ('profile', 'image', 'video', 'audio', 'file')),
    storage_bucket TEXT NOT NULL DEFAULT 'my-chatting-pro-media',
    storage_path TEXT NOT NULL UNIQUE,
    original_filename TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    duration_ms BIGINT,
    width INTEGER,
    height INTEGER,
    upload_status TEXT NOT NULL DEFAULT 'completed' CHECK (upload_status IN ('pending', 'completed', 'deleted')),
    created_at_ms BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_media_owner ON public.media_objects (owner_user_id);
CREATE INDEX IF NOT EXISTS idx_media_conv ON public.media_objects (conversation_id);

-- 8. Messages
CREATE TABLE IF NOT EXISTS public.messages (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    sender_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    client_message_id TEXT NOT NULL,
    message_type TEXT NOT NULL CHECK (message_type IN ('text', 'image', 'video', 'voice', 'audio', 'file', 'call_log')),
    body_text TEXT,
    media_id TEXT REFERENCES public.media_objects(id) ON DELETE SET NULL,
    reply_to_message_id TEXT,
    deleted_for_all_at_ms BIGINT,
    created_at_ms BIGINT NOT NULL,
    UNIQUE (sender_id, client_message_id)
);

CREATE INDEX IF NOT EXISTS idx_messages_conv_cursor ON public.messages (conversation_id, created_at_ms DESC, id DESC);

-- 9. Per-recipient message delivery & read receipts
CREATE TABLE IF NOT EXISTS public.message_receipts (
    message_id TEXT NOT NULL REFERENCES public.messages(id) ON DELETE CASCADE,
    recipient_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    delivered_at_ms BIGINT,
    read_at_ms BIGINT,
    PRIMARY KEY (message_id, recipient_id)
);

CREATE INDEX IF NOT EXISTS idx_receipts_recipient ON public.message_receipts (recipient_id, read_at_ms);

-- 10. WebRTC call history records
CREATE TABLE IF NOT EXISTS public.calls (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    caller_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    call_type TEXT NOT NULL CHECK (call_type IN ('audio', 'video')),
    status TEXT NOT NULL CHECK (status IN ('ringing', 'accepted', 'rejected', 'missed', 'ended', 'failed')),
    started_at_ms BIGINT NOT NULL,
    answered_at_ms BIGINT,
    ended_at_ms BIGINT,
    duration_seconds INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_calls_conv ON public.calls (conversation_id, started_at_ms DESC);

-- 11. WebRTC call participant state
CREATE TABLE IF NOT EXISTS public.call_participants (
    call_id TEXT NOT NULL REFERENCES public.calls(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    state TEXT NOT NULL CHECK (state IN ('invited', 'ringing', 'joined', 'rejected', 'left', 'missed')),
    joined_at_ms BIGINT,
    left_at_ms BIGINT,
    PRIMARY KEY (call_id, user_id)
);

-- 12. User blocking
CREATE TABLE IF NOT EXISTS public.blocked_users (
    blocker_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    blocked_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    created_at_ms BIGINT NOT NULL,
    PRIMARY KEY (blocker_id, blocked_id)
);

CREATE INDEX IF NOT EXISTS idx_blocked_lookup ON public.blocked_users (blocked_id, blocker_id);

-- 13. Abuse / moderation reports
CREATE TABLE IF NOT EXISTS public.reports (
    id TEXT PRIMARY KEY,
    reporter_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    target_user_id TEXT NOT NULL,
    message_id TEXT,
    reason TEXT NOT NULL,
    details TEXT,
    status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'reviewed', 'resolved')),
    created_at_ms BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_reports_status ON public.reports (status, created_at_ms DESC);

-- 14. Non-Firebase notification transports (UnifiedPush / WebSocket foreground)
CREATE TABLE IF NOT EXISTS public.notification_endpoints (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
    transport TEXT NOT NULL CHECK (transport IN ('unified_push', 'websocket_foreground')),
    endpoint_url TEXT,
    updated_at_ms BIGINT NOT NULL,
    UNIQUE (user_id, device_id)
);

-- 15. Sliding-window API rate limits
CREATE TABLE IF NOT EXISTS public.rate_limits (
    bucket_key TEXT PRIMARY KEY,
    count INTEGER NOT NULL,
    window_start_ms BIGINT NOT NULL,
    blocked_until_ms BIGINT
);

-- 16. Privacy-safe security audit log (NEVER stores PINs, tokens, message bodies, or media URLs)
CREATE TABLE IF NOT EXISTS public.audit_logs (
    id TEXT PRIMARY KEY,
    user_id TEXT,
    event_type TEXT NOT NULL,
    outcome TEXT NOT NULL,
    ip_hash TEXT,
    meta_json JSONB,
    created_at_ms BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_user ON public.audit_logs (user_id, created_at_ms DESC);
CREATE INDEX IF NOT EXISTS idx_audit_event_time ON public.audit_logs (event_type, created_at_ms DESC);
