import { DurableObject } from 'cloudflare:workers';
import { Env } from '../config/env';
import { createSupabaseDb } from '../database/supabase';
import { RealtimeEnvelope } from '../websocket/events';

interface SocketAttachment {
  userId: string;
  deviceId: string;
  sessionId: string;
  connectedAtMs: number;
}

/**
 * Per-User Cloudflare Durable Object coordinating authenticated WebSocket
 * connections, presence updates (persisted in Supabase), incoming messages,
 * receipts, and WebRTC signaling.
 */
export class UserRealtimeCoordinator extends DurableObject<Env> {
  private readonly maxBuffer = 100;
  private readonly presenceTimeoutMs = 90_000;
  private lastPingDbUpdateMs = 0;
  private readonly pairAuthCache = new Map<string, number>();
  private readonly callParticipantCache = new Map<string, number>();
  private readonly callSignalBuffer = new Map<string, Array<{ envelope: RealtimeEnvelope; savedAtMs: number }>>();

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    // Internal HTTP dispatch from Worker routes or ConversationCoordinator
    if (url.pathname === '/internal/push' && request.method === 'POST') {
      const envelope = (await request.json()) as RealtimeEnvelope;
      const deliveredCount = this.broadcastToUserSockets(envelope);
      const isCallSignal =
        envelope.type === 'call.ringing' ||
        envelope.type === 'call.accept' ||
        envelope.type === 'call.reject' ||
        envelope.type === 'call.offer' ||
        envelope.type === 'call.answer' ||
        envelope.type === 'call.ice' ||
        envelope.type === 'call.end' ||
        envelope.type === 'call.state' ||
        envelope.type === 'call.missed';
      if (isCallSignal) {
        this.recordCallSignal(envelope);
      }

      const isPersistent =
        envelope.type !== 'presence.update' &&
        envelope.type !== 'typing.start' &&
        envelope.type !== 'typing.stop' &&
        envelope.type !== 'call.ice' &&
        envelope.type !== 'call.offer' &&
        envelope.type !== 'call.answer' &&
        envelope.type !== 'call.ringing' &&
        envelope.type !== 'call.accept';
      if (isPersistent) {
        await this.bufferEvent(envelope);
      }
      return Response.json({ ok: true, deliveredCount });
    }

    if (url.pathname === '/internal/call-signals' && request.method === 'GET') {
      const callId = url.searchParams.get('callId') ?? '';
      const afterMs = Number.parseInt(url.searchParams.get('afterMs') ?? '0', 10);
      const signals = this.getBufferedCallSignals(
        callId,
        Number.isFinite(afterMs) && afterMs > 0 ? afterMs : 0
      );
      return Response.json({ signals });
    }

    if (url.pathname === '/internal/presence' && request.method === 'GET') {
      const sockets = this.ctx.getWebSockets();
      return Response.json({ online: sockets.length > 0, activeSockets: sockets.length });
    }

    // Authenticated WebSocket upgrade
    if (request.headers.get('Upgrade')?.toLowerCase() === 'websocket') {
      const userId = request.headers.get('X-Auth-User-Id');
      const deviceId = request.headers.get('X-Auth-Device-Id');
      const sessionId = request.headers.get('X-Auth-Session-Id');
      if (!userId || !deviceId || !sessionId) {
        return new Response('Missing internal auth headers', { status: 401 });
      }

      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair) as [WebSocket, WebSocket];

      const now = Date.now();
      const attachment: SocketAttachment = {
        userId,
        deviceId,
        sessionId,
        connectedAtMs: now,
      };

      this.ctx.acceptWebSocket(server, [userId, deviceId]);
      server.serializeAttachment(attachment);

      await this.ctx.storage.put('presence_user_id', userId);
      await this.ctx.storage.put('last_active_ms', now);
      await this.ctx.storage.setAlarm(now + this.presenceTimeoutMs);

      // Update online presence in Supabase PostgreSQL & notify conversation peers
      const db = createSupabaseDb(this.env);
      await db.updateUserPresence(userId, true, now);
      this.ctx.waitUntil(this.broadcastPresenceToPeers(userId, true, now));

      // Send initial connection ack
      const ack: RealtimeEnvelope = {
        eventId: crypto.randomUUID(),
        type: 'connected',
        timestampMs: now,
        payload: { userId, deviceId },
      };
      server.send(JSON.stringify(ack));

      // Replay missed buffered events if client supplied ?sinceMs=, or recent call invites on cold start
      const sinceMsParam = Number.parseInt(url.searchParams.get('sinceMs') ?? '0', 10);
      if (Number.isFinite(sinceMsParam) && sinceMsParam > 0) {
        const missed = await this.getBufferedEventsSince(sinceMsParam);
        for (const ev of missed) {
          server.send(JSON.stringify(ev));
        }
      } else {
        const recentCallEvents = (await this.getBufferedEventsSince(now - 45_000)).filter(
          (ev) =>
            ev.type === 'call.invite' ||
            ev.type === 'call.state' ||
            ev.type === 'call.missed'
        );
        for (const ev of recentCallEvents) {
          server.send(JSON.stringify(ev));
        }
      }

      // Also replay any active in-memory call signals from the last 45s
      const replayCutoff = Number.isFinite(sinceMsParam) && sinceMsParam > now - 45_000 ? sinceMsParam : now - 45_000;
      for (const sig of this.getAllRecentCallSignals(replayCutoff)) {
        server.send(JSON.stringify(sig));
      }

      return new Response(null, { status: 101, webSocket: client });
    }

    return new Response('Not found', { status: 404 });
  }

  async alarm(): Promise<void> {
    const userId = await this.ctx.storage.get<string>('presence_user_id');
    if (!userId) return;

    const now = Date.now();
    const lastActiveMs = (await this.ctx.storage.get<number>('last_active_ms')) ?? 0;
    const sockets = this.ctx.getWebSockets();

    if (sockets.length === 0 || now - lastActiveMs > this.presenceTimeoutMs) {
      const db = createSupabaseDb(this.env);
      const offlineAt = lastActiveMs > 0 ? lastActiveMs : now;
      await db.updateUserPresence(userId, false, offlineAt);
      await this.broadcastPresenceToPeers(userId, false, offlineAt);
      return;
    }

    await this.ctx.storage.setAlarm(now + this.presenceTimeoutMs);
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== 'string') return;
    const attachment = ws.deserializeAttachment() as SocketAttachment | null;
    if (!attachment) {
      ws.close(1008, 'Unauthorized');
      return;
    }

    let parsed: { type?: string; payload?: Record<string, unknown> };
    try {
      parsed = JSON.parse(message);
    } catch {
      return;
    }

    if (parsed.type === 'ping') {
      const now = Date.now();
      this.ctx.waitUntil(
        (async () => {
          await this.ctx.storage.put('presence_user_id', attachment.userId);
          await this.ctx.storage.put('last_active_ms', now);
          await this.ctx.storage.setAlarm(now + this.presenceTimeoutMs);
        })()
      );
      if (now - this.lastPingDbUpdateMs >= 30_000) {
        this.lastPingDbUpdateMs = now;
        const db = createSupabaseDb(this.env);
        this.ctx.waitUntil(
          db.updateUserPresence(attachment.userId, true, now).catch(() => undefined)
        );
      }
      ws.send(
        JSON.stringify({
          eventId: crypto.randomUUID(),
          type: 'pong',
          timestampMs: now,
          payload: {},
        } satisfies RealtimeEnvelope)
      );
      return;
    }

    if (parsed.type === 'presence.offline') {
      const now = Date.now();
      const otherSockets = this.ctx.getWebSockets().filter((sock) => sock !== ws);
      if (otherSockets.length === 0) {
        await this.ctx.storage.put('last_active_ms', 0);
        const db = createSupabaseDb(this.env);
        await db.updateUserPresence(attachment.userId, false, now);
        this.ctx.waitUntil(this.broadcastPresenceToPeers(attachment.userId, false, now));
      }
      return;
    }

    if (parsed.type === 'sync.resume') {
      const sinceMs = Number(parsed.payload?.sinceMs ?? 0);
      if (Number.isFinite(sinceMs) && sinceMs > 0) {
        const missed = await this.getBufferedEventsSince(sinceMs);
        for (const ev of missed) {
          ws.send(JSON.stringify(ev));
        }
        for (const sig of this.getAllRecentCallSignals(sinceMs)) {
          ws.send(JSON.stringify(sig));
        }
      }
      return;
    }

    // Typing indicators & WebRTC signaling forwarded to target user's Durable Object
    const targetUserId = typeof parsed.payload?.targetUserId === 'string' ? parsed.payload.targetUserId : null;
    const conversationId = typeof parsed.payload?.conversationId === 'string' ? parsed.payload.conversationId : null;
    const callId = typeof parsed.payload?.callId === 'string' ? parsed.payload.callId : null;

    if (
      targetUserId &&
      (parsed.type === 'typing.start' ||
        parsed.type === 'typing.stop' ||
        parsed.type === 'call.ringing' ||
        parsed.type === 'call.accept' ||
        parsed.type === 'call.reject' ||
        parsed.type === 'call.offer' ||
        parsed.type === 'call.answer' ||
        parsed.type === 'call.ice' ||
        parsed.type === 'call.end')
    ) {
      try {
        const allowed = await this.isForwardingAuthorized(
          attachment.userId,
          targetUserId,
          conversationId,
          callId,
          parsed.type
        );
        if (!allowed) return;

        const envelope: RealtimeEnvelope = {
          eventId: crypto.randomUUID(),
          type: parsed.type,
          timestampMs: Date.now(),
          payload: {
            ...(parsed.payload ?? {}),
            fromUserId: attachment.userId,
          },
        };

        const targetStub = this.env.USER_COORDINATOR.get(
          this.env.USER_COORDINATOR.idFromName(targetUserId)
        );
        await targetStub.fetch('https://do.internal/internal/push', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(envelope),
        });
      } catch {
        // Never allow a single signal dispatch error to crash the WebSocket handler
      }
    }
  }

  private async isForwardingAuthorized(
    userId: string,
    targetUserId: string,
    conversationId: string | null,
    callId: string | null,
    type: string
  ): Promise<boolean> {
    const now = Date.now();
    const db = createSupabaseDb(this.env);

    if (conversationId) {
      const cacheKey = `${conversationId}:${userId}:${targetUserId}`;
      const cachedExpiry = this.pairAuthCache.get(cacheKey);
      if (cachedExpiry && cachedExpiry > now) {
        return true;
      }
      const allowed = await db.checkConversationPair(conversationId, userId, targetUserId);
      if (allowed) {
        if (this.pairAuthCache.size > 100) {
          this.pairAuthCache.clear();
        }
        this.pairAuthCache.set(cacheKey, now + 60_000);
        return true;
      }
      // Fallback for calls when conversation check failed but call participant check passes
      if (!type.startsWith('call.') || !callId) {
        return false;
      }
    }

    if (type.startsWith('call.') && callId) {
      const callKey = `${callId}:${userId}`;
      const cachedExpiry = this.callParticipantCache.get(callKey);
      if (cachedExpiry && cachedExpiry > now) {
        return true;
      }
      const isParticipant = await db.isCallParticipant(callId, userId);
      if (isParticipant) {
        if (this.callParticipantCache.size > 100) {
          this.callParticipantCache.clear();
        }
        this.callParticipantCache.set(callKey, now + 60_000);
      }
      return isParticipant;
    }

    return false;
  }

  private recordCallSignal(envelope: RealtimeEnvelope): void {
    const callId = typeof envelope.payload?.callId === 'string' ? envelope.payload.callId : '';
    if (!callId) return;
    const now = Date.now();
    this.pruneExpiredCallSignals(now);
    const list = this.callSignalBuffer.get(callId) ?? [];
    list.push({ envelope, savedAtMs: now });
    if (list.length > 120) {
      list.splice(0, list.length - 120);
    }
    this.callSignalBuffer.set(callId, list);
  }

  private getBufferedCallSignals(callId: string, afterMs: number): RealtimeEnvelope[] {
    if (!callId) return [];
    const now = Date.now();
    this.pruneExpiredCallSignals(now);
    const list = this.callSignalBuffer.get(callId) ?? [];
    return list
      .filter((item) => item.envelope.timestampMs > afterMs)
      .map((item) => item.envelope);
  }

  private getAllRecentCallSignals(afterMs: number): RealtimeEnvelope[] {
    const now = Date.now();
    this.pruneExpiredCallSignals(now);
    const out: RealtimeEnvelope[] = [];
    for (const list of this.callSignalBuffer.values()) {
      for (const item of list) {
        if (item.envelope.timestampMs > afterMs) {
          out.push(item.envelope);
        }
      }
    }
    out.sort((a, b) => a.timestampMs - b.timestampMs);
    return out;
  }

  private pruneExpiredCallSignals(nowMs: number): void {
    const cutoff = nowMs - 120_000;
    for (const [cid, list] of this.callSignalBuffer.entries()) {
      const kept = list.filter((item) => item.savedAtMs >= cutoff);
      if (kept.length === 0) {
        this.callSignalBuffer.delete(cid);
      } else if (kept.length !== list.length) {
        this.callSignalBuffer.set(cid, kept);
      }
    }
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    const attachment = ws.deserializeAttachment() as SocketAttachment | null;
    try {
      ws.close();
    } catch {
      // ignore close errors
    }
    if (!attachment) return;

    const remaining = this.ctx.getWebSockets().filter((sock) => sock !== ws);
    if (remaining.length === 0) {
      const now = Date.now();
      await this.ctx.storage.put('last_active_ms', 0);
      const db = createSupabaseDb(this.env);
      await db.updateUserPresence(attachment.userId, false, now);
      this.ctx.waitUntil(this.broadcastPresenceToPeers(attachment.userId, false, now));
    }
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    await this.webSocketClose(ws);
  }

  private broadcastToUserSockets(envelope: RealtimeEnvelope): number {
    const payload = JSON.stringify(envelope);
    let sent = 0;
    for (const sock of this.ctx.getWebSockets()) {
      try {
        sock.send(payload);
        sent++;
      } catch {
        // dead socket cleaned up by runtime
      }
    }
    return sent;
  }

  private async bufferEvent(envelope: RealtimeEnvelope): Promise<void> {
    const existing = (await this.ctx.storage.get<RealtimeEnvelope[]>('recent_events')) ?? [];
    existing.push(envelope);
    if (existing.length > this.maxBuffer) {
      existing.splice(0, existing.length - this.maxBuffer);
    }
    await this.ctx.storage.put('recent_events', existing);
  }

  private async broadcastPresenceToPeers(
    userId: string,
    isOnline: boolean,
    timestampMs: number
  ): Promise<void> {
    try {
      const db = createSupabaseDb(this.env);
      const peerIds = await db.listPresencePeerIds(userId);
      if (peerIds.length === 0) return;

      const envelope: RealtimeEnvelope = {
        eventId: crypto.randomUUID(),
        type: 'presence.update',
        timestampMs,
        payload: {
          userId,
          isOnline,
          lastSeenMs: timestampMs,
        },
      };

      await Promise.allSettled(
        peerIds.map((peerId) => {
          const stub = this.env.USER_COORDINATOR.get(this.env.USER_COORDINATOR.idFromName(peerId));
          return stub.fetch('https://do.internal/internal/push', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(envelope),
          });
        })
      );
    } catch {
      // Presence broadcast failures should never disrupt socket lifecycle
    }
  }

  private async getBufferedEventsSince(sinceMs: number): Promise<RealtimeEnvelope[]> {
    const existing = (await this.ctx.storage.get<RealtimeEnvelope[]>('recent_events')) ?? [];
    return existing.filter((ev) => ev.timestampMs > sinceMs);
  }
}
