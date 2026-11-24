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

export class CallsService {
  constructor(
    private readonly env: Env,
    private readonly db: DatabaseClient
  ) {}

  async getIceServers(): Promise<{ iceServers: IceServerDto[]; turnConfigured: boolean }> {
    const primaryStun = this.env.ALLOWED_STUN_URL || 'stun:stun.cloudflare.com:3478';
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
          const data = (await res.json()) as { iceServers?: IceServerDto };
          if (data.iceServers) {
            iceServers.push(data.iceServers);
            turnConfigured = true;
          }
        }
      } catch {
        // Fall through to static TURN or STUN-only if TURN API temporarily unreachable
      }
    }

    if (!turnConfigured && this.env.STATIC_TURN_URLS && this.env.STATIC_TURN_USERNAME && this.env.STATIC_TURN_CREDENTIAL) {
      const urls = this.env.STATIC_TURN_URLS.split(',').map((u) => u.trim()).filter(Boolean);
      if (urls.length > 0) {
        iceServers.push({
          urls,
          username: this.env.STATIC_TURN_USERNAME,
          credential: this.env.STATIC_TURN_CREDENTIAL,
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
