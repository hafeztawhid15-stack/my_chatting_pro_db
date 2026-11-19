-- ============================================================================
-- MY CHATTING PRO — Row Level Security (RLS) & Private Storage Bucket (Migration 0002)
-- All Android clients communicate strictly via Cloudflare Workers (never directly with Supabase).
-- Cloudflare Workers authenticate using SUPABASE_SERVICE_ROLE_KEY stored only as a Worker Secret.
-- ============================================================================

-- Enable RLS on every public table so direct PostgREST access via anon/authenticated keys is blocked
ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.conversation_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.media_objects ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.message_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.calls ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.call_participants ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.blocked_users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.notification_endpoints ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.rate_limits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_logs ENABLE ROW LEVEL SECURITY;

-- Explicitly revoke direct client table privileges from Supabase anon & authenticated roles.
-- Only service_role (used by Cloudflare Worker server-side) retains access.
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon, authenticated;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM anon, authenticated;

-- Defense-in-depth explicit deny policies for anon & authenticated
DROP POLICY IF EXISTS deny_all_users ON public.users;
CREATE POLICY deny_all_users ON public.users FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS deny_all_profiles ON public.user_profiles;
CREATE POLICY deny_all_profiles ON public.user_profiles FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS deny_all_devices ON public.devices;
CREATE POLICY deny_all_devices ON public.devices FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS deny_all_sessions ON public.sessions;
CREATE POLICY deny_all_sessions ON public.sessions FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS deny_all_conversations ON public.conversations;
CREATE POLICY deny_all_conversations ON public.conversations FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS deny_all_conv_members ON public.conversation_members;
CREATE POLICY deny_all_conv_members ON public.conversation_members FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS deny_all_media_objects ON public.media_objects;
CREATE POLICY deny_all_media_objects ON public.media_objects FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS deny_all_messages ON public.messages;
CREATE POLICY deny_all_messages ON public.messages FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS deny_all_receipts ON public.message_receipts;
CREATE POLICY deny_all_receipts ON public.message_receipts FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS deny_all_calls ON public.calls;
CREATE POLICY deny_all_calls ON public.calls FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS deny_all_call_participants ON public.call_participants;
CREATE POLICY deny_all_call_participants ON public.call_participants FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS deny_all_blocked ON public.blocked_users;
CREATE POLICY deny_all_blocked ON public.blocked_users FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS deny_all_reports ON public.reports;
CREATE POLICY deny_all_reports ON public.reports FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS deny_all_notif_endpoints ON public.notification_endpoints;
CREATE POLICY deny_all_notif_endpoints ON public.notification_endpoints FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS deny_all_rate_limits ON public.rate_limits;
CREATE POLICY deny_all_rate_limits ON public.rate_limits FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

DROP POLICY IF EXISTS deny_all_audit_logs ON public.audit_logs;
CREATE POLICY deny_all_audit_logs ON public.audit_logs FOR ALL TO anon, authenticated USING (FALSE) WITH CHECK (FALSE);

-- ============================================================================
-- Private Supabase Storage Bucket: my-chatting-pro-media
-- ============================================================================
INSERT INTO storage.buckets (id, name, public, file_size_limit)
VALUES ('my-chatting-pro-media', 'my-chatting-pro-media', FALSE, 52428800)
ON CONFLICT (id) DO UPDATE
SET public = FALSE,
    file_size_limit = 52428800;

-- Ensure anon/authenticated cannot read or list objects in private media bucket directly
DROP POLICY IF EXISTS deny_private_media_objects ON storage.objects;
CREATE POLICY deny_private_media_objects
ON storage.objects
FOR ALL
TO anon, authenticated
USING (bucket_id <> 'my-chatting-pro-media')
WITH CHECK (bucket_id <> 'my-chatting-pro-media');
