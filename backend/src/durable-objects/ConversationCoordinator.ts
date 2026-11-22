import { DurableObject } from 'cloudflare:workers';
import { Env } from '../config/env';
import { RealtimeEnvelope } from '../websocket/events';

/**
 * Per-Conversation Durable Object acting as an authoritative fan-out coordinator
 * to ensure sequential event ordering and idempotent dispatch to member UserRealtimeCoordinators.
 */
export class ConversationCoordinator extends DurableObject<Env> {
  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === '/internal/fanout' && request.method === 'POST') {
      const body = (await request.json()) as {
        memberIds: string[];
        envelope: RealtimeEnvelope;
        memberIsPrivate?: Record<string, boolean>;
      };

      const seenEvent = await this.ctx.storage.get<boolean>(`dedup:${body.envelope.eventId}`);
      if (seenEvent) {
        return Response.json({ ok: true, deduplicated: true });
      }
      await this.ctx.storage.put(`dedup:${body.envelope.eventId}`, true);

      const dispatches = body.memberIds.map(async (memberId) => {
        const stub = this.env.USER_COORDINATOR.get(this.env.USER_COORDINATOR.idFromName(memberId));
        let envelopeToSend = body.envelope;
        if (body.memberIsPrivate && Object.prototype.hasOwnProperty.call(body.memberIsPrivate, memberId)) {
          const isPriv = Boolean(body.memberIsPrivate[memberId]);
          const payload = { ...(body.envelope.payload as Record<string, unknown>), isPrivate: isPriv };
          if (payload.message && typeof payload.message === 'object') {
            payload.message = { ...(payload.message as Record<string, unknown>), isPrivate: isPriv };
          }
          envelopeToSend = { ...body.envelope, payload };
        }
        await stub.fetch('https://do.internal/internal/push', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(envelopeToSend),
        });
      });

      await Promise.all(dispatches);
      return Response.json({ ok: true, deduplicated: false });
    }

    return new Response('Not found', { status: 404 });
  }
}
