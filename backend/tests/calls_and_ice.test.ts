import { describe, expect, it } from 'vitest';
import { CallsService, normalizeIceUrls } from '../src/modules/calls/calls.service';
import type { Env } from '../src/config/env';
import type { DatabaseClient } from '../src/database/supabase';

describe('CallsService ICE normalization & HTTP signaling fallback', () => {
  it('normalizes bare host:port TURN URLs, cleans quotes/brackets, and rejects web URLs', () => {
    expect(normalizeIceUrls('relay.metered.ca:80')).toEqual(['turn:relay.metered.ca:80']);
    expect(
      normalizeIceUrls('["turn:a.relay.metered.ca:80", "a.relay.metered.ca:443?transport=tcp", "https://bad.example.com"]')
    ).toEqual([
      'turn:a.relay.metered.ca:80',
      'turns:a.relay.metered.ca:443?transport=tcp',
    ]);
    expect(normalizeIceUrls('not a url with spaces ///')).toEqual([]);
  });

  it('always preserves STUN servers even when STATIC_TURN_URLS is malformed or empty', async () => {
    const fakeDb = {} as unknown as DatabaseClient;

    const badEnv = {
      ALLOWED_STUN_URL: 'https://invalid.example.com',
      STATIC_TURN_URLS: 'https://invalid-turn.example.com',
      STATIC_TURN_USERNAME: 'u1',
      STATIC_TURN_CREDENTIAL: 'c1',
    } as unknown as Env;

    const svcBad = new CallsService(badEnv, fakeDb);
    const resBad = await svcBad.getIceServers();
    expect(resBad.turnConfigured).toBe(false);
    expect(resBad.iceServers.length).toBe(1);
    expect(resBad.iceServers[0].urls).toContain('stun:stun.cloudflare.com:3478');
    expect(resBad.iceServers[0].urls).toContain('stun:stun.l.google.com:19302');

    const bareTurnEnv = {
      STATIC_TURN_URLS: 'relay.metered.ca:80',
      STATIC_TURN_USERNAME: ' u1 ',
      STATIC_TURN_CREDENTIAL: ' c1 ',
    } as unknown as Env;

    const svcBare = new CallsService(bareTurnEnv, fakeDb);
    const resBare = await svcBare.getIceServers();
    expect(resBare.turnConfigured).toBe(true);
    expect(resBare.iceServers.length).toBe(2);
    expect(resBare.iceServers[1]).toEqual({
      urls: ['turn:relay.metered.ca:80'],
      username: 'u1',
      credential: 'c1',
    });
  });

  it('enforces call participant check and signal type validation on relayCallSignal', async () => {
    const forwarded: Array<{ url: string; body: string }> = [];
    const fakeDb = {
      listCallParticipantIds: async (callId: string) =>
        callId === 'call-1' ? ['u1', 'u2'] : [],
      listActivePushTokensForUser: async () => [],
    } as unknown as DatabaseClient;

    const fakeEnv = {
      USER_COORDINATOR: {
        idFromName: (name: string) => name,
        get: () => ({
          fetch: async (url: string, init?: RequestInit) => {
            forwarded.push({ url, body: String(init?.body ?? '') });
            return new Response(JSON.stringify({ ok: true }), { status: 200 });
          },
        }),
      },
    } as unknown as Env;

    const svc = new CallsService(fakeEnv, fakeDb);

    await expect(
      svc.relayCallSignal('outsider', 'call-1', { type: 'call.offer', payload: { sdp: 'v=0' } })
    ).rejects.toMatchObject({ statusCode: 403, code: 'forbidden_call' });

    await expect(
      svc.relayCallSignal('u1', 'call-1', { type: 'message.new', payload: {} })
    ).rejects.toMatchObject({ statusCode: 400, code: 'invalid_signal_type' });

    const res = await svc.relayCallSignal('u1', 'call-1', {
      type: 'call.offer',
      conversationId: 'conv-1',
      targetUserId: 'u2',
      payload: { sdp: 'v=0\r\n' },
    });
    expect(res).toEqual({ ok: true, deliveredTo: 1 });
    expect(forwarded.length).toBe(1);
    const parsed = JSON.parse(forwarded[0].body);
    expect(parsed.type).toBe('call.offer');
    expect(parsed.payload.fromUserId).toBe('u1');
    expect(parsed.payload.callId).toBe('call-1');
  });
});
