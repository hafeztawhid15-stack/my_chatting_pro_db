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
  private lastPingDbUpdateMs = 0;

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    // Internal HTTP dispatch from Worker routes or ConversationCoordinator
    if (url.pathname === '/internal/push' && request.method === 'POST') {
      const envelope = (await request.json()) as RealtimeEnvelope;
      const isEphemeral =
        envelope.type === 'presence.update' ||
        envelope.type === 'typing.start' ||
        envelope.type === 'typing.stop';
      if (!isEphemeral) {
        await this.bufferEvent(envelope);
      }
      const deliveredCount = this.broadcastToUserSockets(envelope);
      return Response.json({ ok: true, deliveredCount });
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

      const attachment: SocketAttachment = {
        userId,
        deviceId,
        sessionId,
        connectedAtMs: Date.now(),
      };

      this.ctx.acceptWebSocket(server, [userId, deviceId]);
      server.serializeAttachment(attachment);

      // Update online presence in Supabase PostgreSQL & notify conversation peers
      const now = Date.now();
      const db = createSupabaseDb(this.env);
      await db.updateUserPresence(userId, true, now);
      this.ctx.waitUntil(this.broadcastPresenceToPeers(userId, true, now));

      // Send initial connection ack
      const ack: RealtimeEnvelope = {
        eventId: crypto.randomUUID(),
        type: 'connected',
        timestampMs: Date.now(),
        payload: { userId, deviceId },
      };
      server.send(JSON.stringify(ack));

      // Replay missed buffered events if client supplied ?sinceMs=
      const sinceMsParam = Number.parseInt(url.searchParams.get('sinceMs') ?? '0', 10);
      if (Number.isFinite(sinceMsParam) && sinceMsParam > 0) {
        const missed = await this.getBufferedEventsSince(sinceMsParam);
        for (const ev of missed) {
          server.send(JSON.stringify(ev));
        }
      }

      return new Response(null, { status: 101, webSocket: client });
    }

    return new Response('Not found', { status: 404 });
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
      if (now - this.lastPingDbUpdateMs >= 60_000) {
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

    if (parsed.type === 'sync.resume') {
      const sinceMs = Number(parsed.payload?.sinceMs ?? 0);
      if (Number.isFinite(sinceMs) && sinceMs > 0) {
        const missed = await this.getBufferedEventsSince(sinceMs);
        for (const ev of missed) {
          ws.send(JSON.stringify(ev));
        }
      }
      return;
    }

    // Typing indicators & WebRTC signaling forwarded to target user's Durable Object
    const targetUserId = typeof parsed.payload?.targetUserId === 'string' ? parsed.payload.targetUserId : null;
    const conversationId = typeof parsed.payload?.conversationId === 'string' ? parsed.payload.conversationId : null;

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
      const db = createSupabaseDb(this.env);
      // Verify both users belong to the conversation & are not blocked
      if (conversationId) {
        const allowed = await db.checkConversationPair(conversationId, attachment.userId, targetUserId);
        if (!allowed) return;
      }

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

    const remaining = this.ctx.getWebSockets();
    if (remaining.length === 0) {
      const now = Date.now();
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
