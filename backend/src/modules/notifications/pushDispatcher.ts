import { Env } from '../../config/env';
import { DatabaseClient } from '../../database/supabase';
import { RealtimeEnvelope } from '../../websocket/events';

export interface UnifiedPushHint {
  category: 'message' | 'call' | 'security' | 'account';
  title: string;
  body: string;
  eventId: string;
  conversationId?: string;
  callId?: string;
  isPrivate?: boolean;
}

/**
 * Dispatches real-time events via Cloudflare Durable Objects AND optional
 * user-configured UnifiedPush HTTPS endpoints (zero Firebase / zero FCM).
 */
export async function dispatchUserEventAndPush(
  env: Env,
  db: DatabaseClient,
  userId: string,
  envelope: RealtimeEnvelope,
  pushHint?: UnifiedPushHint
): Promise<void> {
  const stub = env.USER_COORDINATOR.get(env.USER_COORDINATOR.idFromName(userId));
  await stub.fetch('https://do.internal/internal/push', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(envelope),
  });

  if (!pushHint) return;

  try {
    const endpoints = await db.listNotificationEndpointsForUser(userId);
    const httpsTargets = endpoints.filter(
      (e) => e.transport === 'unified_push' && e.endpoint_url && e.endpoint_url.startsWith('https://')
    );
    if (httpsTargets.length === 0) return;

    const payload = JSON.stringify({
      eventId: pushHint.eventId,
      category: pushHint.category,
      title: pushHint.isPrivate ? 'MY CHATTING PRO' : pushHint.title,
      body: pushHint.isPrivate ? 'New encrypted private update' : pushHint.body,
      conversationId: pushHint.isPrivate ? undefined : pushHint.conversationId,
      callId: pushHint.callId,
      isPrivate: Boolean(pushHint.isPrivate),
      timestampMs: Date.now(),
    });

    await Promise.allSettled(
      httpsTargets.map((ep) =>
        fetch(ep.endpoint_url!, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: payload,
        })
      )
    );
  } catch {
    // Non-blocking best-effort UnifiedPush delivery
  }
}

export async function dispatchSecurityAlert(
  env: Env,
  db: DatabaseClient,
  userId: string,
  subType:
    | 'new_login'
    | 'login_pin_changed'
    | 'login_pin_reset'
    | 'recovery_code_updated'
    | 'session_revoked'
    | 'logout_all_devices'
    | 'suspicious_login_attempt'
    | 'account_deleted',
  detail: Record<string, string | number | boolean> = {}
): Promise<void> {
  const now = Date.now();
  const eventId = `sec:${subType}:${now}:${crypto.randomUUID().slice(0, 8)}`;
  const envelope: RealtimeEnvelope = {
    eventId,
    type: 'security.alert',
    timestampMs: now,
    payload: {
      alertType: subType,
      subType,
      ...detail,
      atMs: now,
    },
  };

  await dispatchUserEventAndPush(env, db, userId, envelope, {
    category: 'security',
    title: 'Security Alert',
    body: `Account security activity: ${subType.replace(/_/g, ' ')}`,
    eventId,
  }).catch(() => undefined);
}
