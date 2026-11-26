import { describe, expect, it } from 'vitest';
import { MessagesService } from '../src/modules/messages/messages.service';
import { CallsService } from '../src/modules/calls/calls.service';
import type { Env } from '../src/config/env';
import type { DatabaseClient } from '../src/database/supabase';
import type { StorageClient } from '../src/storage/supabaseStorage';

describe('Media & Message Delete (Delete for Me vs Delete for Everyone)', () => {
  const createEnvWithFanout = (fanouts: Array<Record<string, unknown>>) =>
    ({
      MEDIA_BUCKET_NAME: 'my-chatting-pro-media',
      CONVERSATION_COORDINATOR: {
        idFromName: (name: string) => name,
        get: () => ({
          fetch: async (_url: string, init?: RequestInit) => {
            if (init?.body) {
              fanouts.push(JSON.parse(String(init.body)) as Record<string, unknown>);
            }
            return new Response(JSON.stringify({ ok: true }), { status: 200 });
          },
        }),
      },
    }) as unknown as Env;

  it('allows both sender and recipient to Delete for Me without deleting storage or notifying peer', async () => {
    const hiddenEntries = new Set<string>();
    const deleteTxCalls: string[] = [];
    const deletedStoragePaths: string[] = [];
    const fanouts: Array<Record<string, unknown>> = [];

    const db = {
      findMessageForDelete: async (messageId: string) =>
        messageId === 'msg-img-1'
          ? { id: 'msg-img-1', conversation_id: 'conv-1', sender_id: 'user-a', media_id: 'media-1' }
          : null,
      getConversationMembers: async (conversationId: string) =>
        conversationId === 'conv-1' ? ['user-a', 'user-b'] : [],
      getConversationMemberPrivacy: async () => false,
      hideMessageForUser: async (userId: string, messageId: string) => {
        hiddenEntries.add(`${userId}:${messageId}`);
      },
      deleteOwnMessageTx: async (messageId: string) => {
        deleteTxCalls.push(messageId);
      },
    } as unknown as DatabaseClient;

    const storage = {
      deleteObjects: async (_bucket: string, paths: string[]) => {
        deletedStoragePaths.push(...paths);
      },
    } as unknown as StorageClient;

    const svc = new MessagesService(createEnvWithFanout(fanouts), db, storage);

    // Non-member cannot hide someone else's message
    await expect(svc.deleteMessageForMe('outsider', 'msg-img-1', false)).rejects.toMatchObject({
      statusCode: 403,
      code: 'forbidden_conversation',
    });

    // Missing message returns 404
    await expect(svc.deleteMessageForMe('user-b', 'missing-msg', false)).rejects.toMatchObject({
      statusCode: 404,
      code: 'message_not_found',
    });

    // Recipient deletes for me
    await svc.deleteMessageForMe('user-b', 'msg-img-1', false);
    expect(hiddenEntries.has('user-b:msg-img-1')).toBe(true);
    expect(hiddenEntries.has('user-a:msg-img-1')).toBe(false);

    // Sender deletes for me
    await svc.deleteMessageForMe('user-a', 'msg-img-1', false);
    expect(hiddenEntries.has('user-a:msg-img-1')).toBe(true);

    // Delete for Me MUST NOT delete global DB message, storage objects, or fanout WS events
    expect(deleteTxCalls).toEqual([]);
    expect(deletedStoragePaths).toEqual([]);
    expect(fanouts).toEqual([]);
  });

  it('enforces private-scope isolation on Delete for Me', async () => {
    const hiddenEntries: string[] = [];
    const db = {
      findMessageForDelete: async () => ({
        id: 'priv-msg-1',
        conversation_id: 'conv-priv',
        sender_id: 'user-a',
        media_id: 'media-priv',
      }),
      getConversationMembers: async () => ['user-a', 'user-b'],
      getConversationMemberPrivacy: async (_convId: string, _userId: string) => true,
      hideMessageForUser: async (userId: string, messageId: string) => {
        hiddenEntries.push(`${userId}:${messageId}`);
      },
    } as unknown as DatabaseClient;

    const svc = new MessagesService(createEnvWithFanout([]), db);

    // Trying to delete a private-chat message via regular scope must reject
    await expect(svc.deleteMessageForMe('user-b', 'priv-msg-1', false)).rejects.toMatchObject({
      statusCode: 403,
      code: 'forbidden_private_scope',
    });

    // Private scope succeeds
    await svc.deleteMessageForMe('user-b', 'priv-msg-1', true);
    expect(hiddenEntries).toEqual(['user-b:priv-msg-1']);
  });

  it('enforces sender-only Delete for Everyone, cleans storage media, and notifies peers', async () => {
    const deleteTxCalls: Array<{ messageId: string; mediaId: string | null }> = [];
    const deletedStorageCalls: Array<{ bucket: string; paths: string[] }> = [];
    const fanouts: Array<Record<string, unknown>> = [];

    const db = {
      findMessageForDelete: async (messageId: string) =>
        messageId === 'msg-vid-1'
          ? { id: 'msg-vid-1', conversation_id: 'conv-1', sender_id: 'user-a', media_id: 'vid-media-1' }
          : null,
      getConversationMembers: async () => ['user-a', 'user-b'],
      getConversationMemberPrivacy: async () => false,
      getMembersPrivateFlags: async () => ({ 'user-a': false, 'user-b': false }),
      findMediaObjectById: async (mediaId: string) =>
        mediaId === 'vid-media-1'
          ? { storage_bucket: 'my-chatting-pro-media', storage_path: 'videos/conv-1/vid-media-1.mp4' }
          : null,
      deleteOwnMessageTx: async (messageId: string, _convId: string, mediaId: string | null) => {
        deleteTxCalls.push({ messageId, mediaId });
      },
    } as unknown as DatabaseClient;

    const storage = {
      deleteObjects: async (bucket: string, paths: string[]) => {
        deletedStorageCalls.push({ bucket, paths });
      },
    } as unknown as StorageClient;

    const svc = new MessagesService(createEnvWithFanout(fanouts), db, storage);

    // Recipient MUST NOT be able to Delete for Everyone
    await expect(svc.deleteOwnMessage('user-b', 'msg-vid-1', false)).rejects.toMatchObject({
      statusCode: 403,
      code: 'forbidden_delete',
    });
    expect(deleteTxCalls).toEqual([]);
    expect(deletedStorageCalls).toEqual([]);

    // Sender deletes for everyone
    await svc.deleteOwnMessage('user-a', 'msg-vid-1', false);
    expect(deleteTxCalls).toEqual([{ messageId: 'msg-vid-1', mediaId: 'vid-media-1' }]);
    expect(deletedStorageCalls).toEqual([
      { bucket: 'my-chatting-pro-media', paths: ['videos/conv-1/vid-media-1.mp4'] },
    ]);
    expect(fanouts.length).toBe(1);
    const envelope = fanouts[0].envelope as { type: string; payload: { messageId: string } };
    expect(envelope.type).toBe('message.deleted');
    expect(envelope.payload.messageId).toBe('msg-vid-1');
  });
});

describe('Call History Delete System & Per-User Isolation', () => {
  const createCallDb = () => {
    const records: Record<
      string,
      { callId: string; participants: Record<string, { deletedAtMs: number | null }> }
    > = {
      'c-1': { callId: 'c-1', participants: { 'user-a': { deletedAtMs: null }, 'user-b': { deletedAtMs: null } } },
      'c-2': { callId: 'c-2', participants: { 'user-a': { deletedAtMs: null }, 'user-b': { deletedAtMs: null } } },
      'c-3': { callId: 'c-3', participants: { 'user-b': { deletedAtMs: null }, 'user-c': { deletedAtMs: null } } },
    };

    const db = {
      findCallById: async (callId: string) =>
        records[callId] ? { id: callId, status: 'ended', answered_at_ms: 1000, ended_at_ms: 5000 } : null,
      isCallParticipant: async (callId: string, userId: string) => Boolean(records[callId]?.participants[userId]),
      filterParticipantCallIds: async (userId: string, callIds: string[]) =>
        callIds.filter((id) => Boolean(records[id]?.participants[userId])),
      deleteCallForUser: async (userId: string, callId: string, nowMs: number) => {
        if (records[callId]?.participants[userId]) {
          records[callId].participants[userId].deletedAtMs = nowMs;
        }
      },
      deleteCallsBatchForUser: async (userId: string, callIds: string[], nowMs: number) => {
        for (const id of callIds) {
          if (records[id]?.participants[userId]) {
            records[id].participants[userId].deletedAtMs = nowMs;
          }
        }
      },
      clearAllCallsForUser: async (userId: string, nowMs: number) => {
        for (const rec of Object.values(records)) {
          if (rec.participants[userId] && rec.participants[userId].deletedAtMs === null) {
            rec.participants[userId].deletedAtMs = nowMs;
          }
        }
      },
    } as unknown as DatabaseClient;

    return { db, records };
  };

  it('enforces 404 and 403 ownership on single call history delete and isolates per-user deletion', async () => {
    const { db, records } = createCallDb();
    const svc = new CallsService({} as Env, db);

    await expect(svc.deleteCallHistoryItem('user-a', 'non-existent')).rejects.toMatchObject({
      statusCode: 404,
      code: 'call_not_found',
    });

    await expect(svc.deleteCallHistoryItem('user-a', 'c-3')).rejects.toMatchObject({
      statusCode: 403,
      code: 'forbidden_call',
    });

    await svc.deleteCallHistoryItem('user-a', 'c-1');
    expect(records['c-1'].participants['user-a'].deletedAtMs).not.toBeNull();
    // Peer's call history MUST remain intact
    expect(records['c-1'].participants['user-b'].deletedAtMs).toBeNull();
  });

  it('handles multi-select batch delete safely and rejects batches containing unauthorized calls', async () => {
    const { db, records } = createCallDb();
    const svc = new CallsService({} as Env, db);

    await expect(svc.deleteCallHistoryBatch('user-a', [])).rejects.toMatchObject({
      statusCode: 400,
      code: 'invalid_call_ids',
    });

    // Reject if batch includes another user's call ID
    await expect(svc.deleteCallHistoryBatch('user-a', ['c-1', 'c-3'])).rejects.toMatchObject({
      statusCode: 403,
      code: 'forbidden_call',
    });
    expect(records['c-1'].participants['user-a'].deletedAtMs).toBeNull();

    // Valid batch deduplicates and deletes only for caller
    const res = await svc.deleteCallHistoryBatch('user-a', ['c-1', 'c-2', 'c-1']);
    expect(res.deletedCount).toBe(2);
    expect(records['c-1'].participants['user-a'].deletedAtMs).not.toBeNull();
    expect(records['c-2'].participants['user-a'].deletedAtMs).not.toBeNull();
    expect(records['c-1'].participants['user-b'].deletedAtMs).toBeNull();
    expect(records['c-2'].participants['user-b'].deletedAtMs).toBeNull();
  });

  it('clears all call history for caller without affecting other users', async () => {
    const { db, records } = createCallDb();
    const svc = new CallsService({} as Env, db);

    await svc.clearAllCallHistory('user-a');
    expect(records['c-1'].participants['user-a'].deletedAtMs).not.toBeNull();
    expect(records['c-2'].participants['user-a'].deletedAtMs).not.toBeNull();
    // user-b and user-c remain completely untouched
    expect(records['c-1'].participants['user-b'].deletedAtMs).toBeNull();
    expect(records['c-2'].participants['user-b'].deletedAtMs).toBeNull();
    expect(records['c-3'].participants['user-b'].deletedAtMs).toBeNull();
    expect(records['c-3'].participants['user-c'].deletedAtMs).toBeNull();
  });
});
