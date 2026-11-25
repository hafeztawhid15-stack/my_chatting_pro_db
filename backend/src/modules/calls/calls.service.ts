import { Env } from '../../config/env';
import { DatabaseClient } from '../../database/supabase';
import { ApiError } from '../../middleware/errorHandler';
import { RealtimeEnvelope } from '../../websocket/events';
import { ConversationsService } from '../conversations/conversations.service';
import { dispatchUserEventAndPush } from '../notifications/pushDispatcher';

export interface IceServerDto {
  urls: string[];
  username?: string;
  credential?: string;
}

const ALLOWED_SIGNAL_TYPES = new Set([
  'call.ringing',
  'call.accept',
  'call.reject',
  'call.offer',
  'call.answer',
  'call.ice',
  'call.end',
]);

export function normalizeIceUrls(rawUrls: string | string[] | undefined | null): string[] {
  const rawList = Array.isArray(rawUrls)
    ? rawUrls
    : typeof rawUrls === 'string'
      ? rawUrls
          .replace(/[\[\]"']/g, ' ')
          .split(/[\s,;]+/)
      : [];

  const normalized: string[] = [];
  for (const item of rawList) {
    const trimmed = item.trim();
    if (!trimmed) continue;

    if (/^(stuns?|turns?):[^\s]+$/i.test(trimmed)) {
      normalized.push(trimmed);
      continue;
    }

    // Reject web/socket URLs (common copy-paste mistake such as https://...)
    if (/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(trimmed)) {
      continue;
    }

    // Auto-normalize bare host[:port][?transport=...] into turn:/turns:
    if (/^[a-zA-Z0-9.-]+(:\d{1,5})?(\?transport=(udp|tcp))?$/i.test(trimmed)) {
      const prefix = /:443(\?|$)/.test(trimmed) ? 'turns:' : 'turn:';
      normalized.push(`${prefix}${trimmed}`);
    }
  }
  return Array.from(new Set(normalized));
}

export class CallsService {
  constructor(
    private readonly env: Env,
    private readonly db: DatabaseClient
  ) {}

  async getIceServers(): Promise<{ iceServers: IceServerDto[]; turnConfigured: boolean }> {
    const primaryStun = normalizeIceUrls(this.env.ALLOWED_STUN_URL)[0] || 'stun:stun.cloudflare.com:3478';
    const stunUrls = Array.from(
      new Set([
        primaryStun,
        'stun:stun.cloudflare.com:3478',
        'stun:stun.l.google.com:19302',
        'stun:stun1.l.google.com:19302',
        'stun:stun2.l.google.com:19302',
        'stun:global.stun.twilio.com:3478',
      ])
    );
    const iceServers: IceServerDto[] = [{ urls: stunUrls }];
    let turnConfigured = false;

    if (this.env.CLOUDFLARE_TURN_TOKEN_ID && this.env.CLOUDFLARE_TURN_API_TOKEN) {
      try {
        const res = await fetch(
          `https://rtc.live.cloudflare.com/v1/turn/keys/${this.env.CLOUDFLARE_TURN_TOKEN_ID}/credentials/generate`,
          {
            method: 'POST',
            headers: {
              Authorization: `Bearer ${this.env.CLOUDFLARE_TURN_API_TOKEN}`,
              'Content-Type': 'application/json',
            },
            body: JSON.stringify({ ttl: 3600 }),
          }
        );
        if (res.ok) {
          const data = (await res.json()) as {
            iceServers?:
              | { urls?: string | string[]; username?: string; credential?: string }
              | Array<{ urls?: string | string[]; username?: string; credential?: string }>;
          };
          const cfEntries = Array.isArray(data.iceServers)
            ? data.iceServers
            : data.iceServers
              ? [data.iceServers]
              : [];
          for (const entry of cfEntries) {
            const urls = normalizeIceUrls(entry.urls);
            if (urls.length > 0) {
              iceServers.push({
                urls,
                username: entry.username,
                credential: entry.credential,
              });
              turnConfigured = true;
            }
          }
        }
      } catch {
        // Fall through to static TURN or STUN-only if TURN API temporarily unreachable
      }
    }

    if (!turnConfigured && this.env.STATIC_TURN_URLS && this.env.STATIC_TURN_USERNAME && this.env.STATIC_TURN_CREDENTIAL) {
      const urls = normalizeIceUrls(this.env.STATIC_TURN_URLS);
      if (urls.length > 0) {
        iceServers.push({
          urls,
          username: this.env.STATIC_TURN_USERNAME.trim(),
          credential: this.env.STATIC_TURN_CREDENTIAL.trim(),
        });
        turnConfigured = true;
      }
    }

    return { iceServers, turnConfigured };
  }

  async initiateCall(
    callerId: string,
    input: { conversationId: string; calleeId: string; callType: 'audio' | 'video' }
  ) {
    const convService = new ConversationsService(this.db);
    const members = await convService.assertMember(input.conversationId, callerId, false);
    if (!members.includes(input.calleeId) || input.calleeId === callerId) {
      throw new ApiError(400, 'invalid_callee', 'Callee must be another member of the conversation.');
    }

    const blocked = await this.db.isBlockedBetween(callerId, input.calleeId);
    if (blocked) {
      throw new ApiError(403, 'user_blocked', 'Calling is unavailable because a block exists.');
    }

    const callerProfile = await this.db.getUserProfileBasic(callerId);

    const callId = crypto.randomUUID();
    const now = Date.now();
    await this.db.initiateCallTx({
      callId,
      conversationId: input.conversationId,
      callerId,
      calleeId: input.calleeId,
      callType: input.callType,
      nowMs: now,
    });

    const inviteEnvelope: RealtimeEnvelope = {
      eventId: `call_invite:${callId}`,
      type: 'call.invite',
      timestampMs: now,
      payload: {
        callId,
        conversationId: input.conversationId,
        callerId,
        callerName: callerProfile?.display_name ?? 'MY CHATTING PRO User',
        callerAvatarMediaId: callerProfile?.avatar_media_id ?? null,
        callType: input.callType,
      },
    };

    await dispatchUserEventAndPush(this.env, this.db, input.calleeId, inviteEnvelope, {
      category: 'call',
      title: input.callType === 'video' ? 'Incoming Video Call' : 'Incoming Audio Call',
      body: `${callerProfile?.display_name ?? 'Someone'} is calling you`,
      eventId: inviteEnvelope.eventId,
      conversationId: input.conversationId,
      callId,
    });

    return {
      callId,
      conversationId: input.conversationId,
      callType: input.callType,
      status: 'ringing',
      startedAtMs: now,
    };
  }

  async updateCallState(
    userId: string,
    callId: string,
    action: 'accept' | 'reject' | 'end' | 'missed'
  ) {
    const isParticipant = await this.db.isCallParticipant(callId, userId);
    if (!isParticipant) {
      throw new ApiError(403, 'forbidden_call', 'You are not a participant of this call.');
    }

    const call = await this.db.findCallById(callId);
    if (!call) {
      throw new ApiError(404, 'call_not_found', 'Call record not found.');
    }

    const now = Date.now();
    const participants = await this.db.listCallParticipantIds(callId);
    const otherParticipants = participants.filter((pid) => pid !== userId);

    if (action === 'accept') {
      await this.assertNoBlockWithPeers(userId, otherParticipants, callId);
      await this.db.acceptCallTx(callId, userId, now);
      const envelope: RealtimeEnvelope = {
        eventId: `call_state:${callId}:accepted:${now}`,
        type: 'call.state',
        timestampMs: now,
        payload: { callId, status: 'accepted', byUserId: userId },
      };
      await Promise.allSettled(
        otherParticipants.map((pid) => dispatchUserEventAndPush(this.env, this.db, pid, envelope))
      );
      return { callId, status: 'accepted', answeredAtMs: call.answered_at_ms ?? now };
    }

    const nextStatus = action === 'reject' ? 'rejected' : action === 'missed' ? 'missed' : 'ended';
    const participantState = action === 'reject' ? 'rejected' : action === 'missed' ? 'missed' : 'left';
    const durationSeconds =
      call.answered_at_ms && now > call.answered_at_ms
        ? Math.floor((now - call.answered_at_ms) / 1000)
        : 0;

    await this.db.finishCallTx(callId, userId, nextStatus, participantState, now, durationSeconds);

    const callerProfile = await this.db.getUserProfileBasic(userId);
    const envelope: RealtimeEnvelope = {
      eventId: `call_state:${callId}:${nextStatus}:${now}`,
      type: action === 'missed' ? 'call.missed' : 'call.state',
      timestampMs: now,
      payload: {
        callId,
        status: nextStatus,
        byUserId: userId,
        peerName: callerProfile?.display_name ?? 'MY CHATTING PRO User',
        durationSeconds,
      },
    };
    await Promise.allSettled(
      otherParticipants.map((pid) =>
        dispatchUserEventAndPush(
          this.env,
          this.db,
          pid,
          envelope,
          action === 'missed'
            ? {
                category: 'call',
                title: 'Missed Call',
                body: `Missed call from ${callerProfile?.display_name ?? 'a contact'}`,
                eventId: envelope.eventId,
                callId,
              }
            : undefined
        )
      )
    );

    return { callId, status: nextStatus, endedAtMs: now, durationSeconds };
  }

  async getCallStatus(userId: string, callId: string) {
    const isParticipant = await this.db.isCallParticipant(callId, userId);
    if (!isParticipant) {
      throw new ApiError(403, 'forbidden_call', 'You are not a participant of this call.');
    }

    const call = await this.db.findCallById(callId);
    if (!call) {
      throw new ApiError(404, 'call_not_found', 'Call record not found.');
    }

    return {
      callId: call.id,
      status: call.status,
      answeredAtMs: call.answered_at_ms ?? null,
      endedAtMs: call.ended_at_ms ?? null,
    };
  }

  async relayCallSignal(
    userId: string,
    callId: string,
    input: {
      type?: string;
      conversationId?: string;
      targetUserId?: string;
      payload?: Record<string, unknown>;
    }
  ): Promise<{ ok: true; deliveredTo: number }> {
    const signalType = input.type ?? '';
    if (!ALLOWED_SIGNAL_TYPES.has(signalType)) {
      throw new ApiError(400, 'invalid_signal_type', 'Unsupported call signaling type.');
    }

    const participants = await this.db.listCallParticipantIds(callId);
    if (!participants.includes(userId)) {
      throw new ApiError(403, 'forbidden_call', 'You are not a participant of this call.');
    }

    const recipients = input.targetUserId
      ? participants.filter((pid) => pid === input.targetUserId && pid !== userId)
      : participants.filter((pid) => pid !== userId);

    if (recipients.length === 0) {
      return { ok: true, deliveredTo: 0 };
    }

    if (signalType !== 'call.end' && signalType !== 'call.reject') {
      await this.assertNoBlockWithPeers(userId, recipients, callId);
    }

    const now = Date.now();
    const safePayload: Record<string, unknown> = {
      ...(input.payload ?? {}),
      callId,
      fromUserId: userId,
    };
    if (input.conversationId) {
      safePayload.conversationId = input.conversationId;
    }

    const envelope: RealtimeEnvelope = {
      eventId: `sig:${signalType}:${callId}:${crypto.randomUUID()}`,
      type: signalType as RealtimeEnvelope['type'],
      timestampMs: now,
      payload: safePayload,
    };

    const results = await Promise.allSettled(
      recipients.map((pid) => dispatchUserEventAndPush(this.env, this.db, pid, envelope))
    );
    const deliveredTo = results.filter((r) => r.status === 'fulfilled').length;
    return { ok: true, deliveredTo };
  }

  async pullCallSignals(
    userId: string,
    callId: string,
    afterMs = 0
  ): Promise<{ signals: RealtimeEnvelope[] }> {
    const participants = await this.db.listCallParticipantIds(callId);
    if (!participants.includes(userId)) {
      throw new ApiError(403, 'forbidden_call', 'You are not a participant of this call.');
    }

    const otherParticipants = participants.filter((pid) => pid !== userId);
    await this.assertNoBlockWithPeers(userId, otherParticipants, callId);

    const stub = this.env.USER_COORDINATOR.get(this.env.USER_COORDINATOR.idFromName(userId));
    const url = `https://do.internal/internal/call-signals?callId=${encodeURIComponent(callId)}&afterMs=${encodeURIComponent(String(afterMs))}`;
    const res = await stub.fetch(url, { method: 'GET' });
    if (!res.ok) {
      return { signals: [] };
    }
    const data = (await res.json()) as { signals?: RealtimeEnvelope[] };
    return { signals: Array.isArray(data.signals) ? data.signals : [] };
  }

  async terminateActiveCallsBetween(userA: string, userB: string, actorUserId: string): Promise<void> {
    const now = Date.now();
    const terminated = await this.db.finishActiveCallsBetweenUsers(userA, userB, now);
    if (terminated.length === 0) return;

    await Promise.allSettled(
      terminated.flatMap((call) => {
        const stateEnvelope: RealtimeEnvelope = {
          eventId: `call_state:${call.callId}:ended_block:${now}`,
          type: 'call.state',
          timestampMs: now,
          payload: {
            callId: call.callId,
            conversationId: call.conversationId,
            status: 'ended',
            reason: 'user_blocked',
            byUserId: actorUserId,
          },
        };
        const endEnvelope: RealtimeEnvelope = {
          eventId: `call_end:${call.callId}:block:${now}`,
          type: 'call.end',
          timestampMs: now,
          payload: {
            callId: call.callId,
            conversationId: call.conversationId,
            reason: 'user_blocked',
            fromUserId: actorUserId,
          },
        };
        return call.participantIds.flatMap((pid) => [
          dispatchUserEventAndPush(this.env, this.db, pid, stateEnvelope),
          dispatchUserEventAndPush(this.env, this.db, pid, endEnvelope),
        ]);
      })
    );
  }

  private async assertNoBlockWithPeers(
    userId: string,
    peerIds: string[],
    callId?: string
  ): Promise<void> {
    for (const pid of peerIds) {
      if (await this.db.isBlockedBetween(userId, pid)) {
        if (callId) {
          await this.db.finishActiveCallsBetweenUsers(userId, pid, Date.now()).catch(() => undefined);
        }
        throw new ApiError(403, 'user_blocked', 'Calling is unavailable because a block exists.');
      }
    }
  }

  async getCallHistory(userId: string, filterRaw?: string, limitRaw?: number) {
    const allowedFilters = new Set(['all', 'incoming', 'outgoing', 'missed', 'rejected']);
    const filter = filterRaw && allowedFilters.has(filterRaw) ? filterRaw : 'all';
    const limit = Math.min(Math.max(limitRaw ?? 60, 1), 120);
    const rows = await this.db.listUserCalls(userId, filter, limit);
    return rows.map((r) => ({
      callId: r.call_id,
      conversationId: r.conversation_id,
      callerId: r.caller_id,
      peerUserId: r.peer_user_id,
      peerDisplayName: r.peer_display_name,
      peerAvatarMediaId: r.peer_avatar_media_id,
      callType: r.call_type,
      status: r.status,
      direction: r.direction,
      startedAtMs: r.started_at_ms,
      answeredAtMs: r.answered_at_ms,
      endedAtMs: r.ended_at_ms,
      durationSeconds: r.duration_seconds,
    }));
  }
}
