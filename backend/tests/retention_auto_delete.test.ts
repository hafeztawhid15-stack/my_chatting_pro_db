import { describe, expect, it } from 'vitest';
import { AUTO_DELETE_RETENTION_DAYS, AUTO_DELETE_RETENTION_MS } from '../src/config/constants';
import type { Env } from '../src/config/env';
import type { DatabaseClient } from '../src/database/supabase';
import { MediaService } from '../src/modules/media/media.service';
import {
  computeRetentionCutoffMs,
  runRetentionCleanup,
} from '../src/modules/retention/retention.service';
import type { StorageClient } from '../src/storage/supabaseStorage';

describe('20-Day Automatic Retention & Auto-Delete System', () => {
  it('uses an exact 20-day retention window', () => {
    expect(AUTO_DELETE_RETENTION_DAYS).toBe(20);
    expect(AUTO_DELETE_RETENTION_MS).toBe(20 * 24 * 60 * 60 * 1000);

    const nowMs = 1_800_000_000_000;
    expect(computeRetentionCutoffMs(nowMs)).toBe(nowMs - 1_728_000_000);
  });

  it('deletes expired non-profile storage objects before calling DB retention purge RPC', async () => {
    const nowMs = 1_800_000_000_000;
    const expectedCutoff = nowMs - AUTO_DELETE_RETENTION_MS;
    const callOrder: string[] = [];
    const deletedBuckets: Array<{ bucket: string; paths: string[] }> = [];
    const rpcCalls: Array<{ cutoffMs: number; mediaIds: string[] }> = [];

    let listCalled = 0;
    const db = {
      listExpiredNonProfileMediaObjects: async (cutoffMs: number) => {
        listCalled += 1;
        expect(cutoffMs).toBe(expectedCutoff);
        callOrder.push(`list:${listCalled}`);
        if (listCalled === 1) {
          return [
            {
              id: 'media-img-old',
              storage_bucket: 'my-chatting-pro-media',
              storage_path: 'conversations/c1/images/media-img-old.jpg',
            },
            {
              id: 'media-vid-old',
              storage_bucket: 'my-chatting-pro-media',
              storage_path: 'conversations/c1/videos/media-vid-old.mp4',
            },
          ];
        }
        return [];
      },
      purgeExpiredRetentionData: async (cutoffMs: number, purgedMediaIds: string[]) => {
        callOrder.push('purge_db');
        rpcCalls.push({ cutoffMs, mediaIds: purgedMediaIds });
        return {
          deletedMessages: 12,
          deletedCalls: 4,
          deletedMediaRows: purgedMediaIds.length,
        };
      },
    } as unknown as DatabaseClient;

    const storage = {
      deleteObjects: async (bucket: string, paths: string[]) => {
        callOrder.push('delete_storage');
        deletedBuckets.push({ bucket, paths });
      },
    } as unknown as StorageClient;

    const result = await runRetentionCleanup(db, storage, nowMs, {
      maxBatches: 4,
      batchSize: 100,
    });

    expect(callOrder).toEqual(['list:1', 'delete_storage', 'purge_db']);
    expect(deletedBuckets).toEqual([
      {
        bucket: 'my-chatting-pro-media',
        paths: [
          'conversations/c1/images/media-img-old.jpg',
          'conversations/c1/videos/media-vid-old.mp4',
        ],
      },
    ]);
    expect(rpcCalls).toEqual([
      {
        cutoffMs: expectedCutoff,
        mediaIds: ['media-img-old', 'media-vid-old'],
      },
    ]);
    expect(result).toEqual({
      deletedStorageFiles: 2,
      deletedMessages: 12,
      deletedCalls: 4,
      deletedMediaRows: 2,
    });
  });

  it('still purges expired messages and calls even when no expired media objects exist', async () => {
    const nowMs = 1_800_000_000_000;
    let storageCalls = 0;
    let dbPurgeCalls = 0;

    const db = {
      listExpiredNonProfileMediaObjects: async () => [],
      purgeExpiredRetentionData: async (cutoffMs: number, mediaIds: string[]) => {
        dbPurgeCalls += 1;
        expect(cutoffMs).toBe(nowMs - AUTO_DELETE_RETENTION_MS);
        expect(mediaIds).toEqual([]);
        return { deletedMessages: 5, deletedCalls: 2, deletedMediaRows: 0 };
      },
    } as unknown as DatabaseClient;

    const storage = {
      deleteObjects: async () => {
        storageCalls += 1;
      },
    } as unknown as StorageClient;

    const result = await runRetentionCleanup(db, storage, nowMs);
    expect(storageCalls).toBe(0);
    expect(dbPurgeCalls).toBe(1);
    expect(result.deletedMessages).toBe(5);
    expect(result.deletedCalls).toBe(2);
  });

  it('does not delete DB media rows if storage deletion fails', async () => {
    let dbPurgeCalled = false;
    const db = {
      listExpiredNonProfileMediaObjects: async () => [
        {
          id: 'media-1',
          storage_bucket: 'my-chatting-pro-media',
          storage_path: 'conversations/c1/images/media-1.jpg',
        },
      ],
      purgeExpiredRetentionData: async () => {
        dbPurgeCalled = true;
        return { deletedMessages: 0, deletedCalls: 0, deletedMediaRows: 0 };
      },
    } as unknown as DatabaseClient;

    const storage = {
      deleteObjects: async () => {
        throw new Error('Temporary storage outage');
      },
    } as unknown as StorageClient;

    await expect(runRetentionCleanup(db, storage, Date.now())).rejects.toThrow(
      'Temporary storage outage'
    );
    expect(dbPurgeCalled).toBe(false);
  });

  it('rejects expired chat media download while keeping profile avatars accessible beyond 20 days', async () => {
    const oldCreatedMs = Date.now() - AUTO_DELETE_RETENTION_MS - 60_000;
    const freshCreatedMs = Date.now() - 24 * 60 * 60 * 1000;

    const db = {
      findMediaObjectById: async (mediaId: string) => {
        if (mediaId === 'expired-video') {
          return {
            id: 'expired-video',
            owner_user_id: 'user-a',
            conversation_id: 'conv-1',
            category: 'video',
            storage_bucket: 'my-chatting-pro-media',
            storage_path: 'conversations/conv-1/videos/expired-video.mp4',
            original_filename: 'clip.mp4',
            mime_type: 'video/mp4',
            size_bytes: 2048,
            upload_status: 'completed',
            created_at_ms: oldCreatedMs,
          };
        }
        if (mediaId === 'old-avatar') {
          return {
            id: 'old-avatar',
            owner_user_id: 'user-a',
            conversation_id: null,
            category: 'profile',
            storage_bucket: 'my-chatting-pro-media',
            storage_path: 'users/user-a/profile/old-avatar.jpg',
            original_filename: 'avatar.jpg',
            mime_type: 'image/jpeg',
            size_bytes: 1024,
            upload_status: 'completed',
            created_at_ms: oldCreatedMs,
          };
        }
        if (mediaId === 'fresh-image') {
          return {
            id: 'fresh-image',
            owner_user_id: 'user-a',
            conversation_id: 'conv-1',
            category: 'image',
            storage_bucket: 'my-chatting-pro-media',
            storage_path: 'conversations/conv-1/images/fresh-image.jpg',
            original_filename: 'pic.jpg',
            mime_type: 'image/jpeg',
            size_bytes: 1024,
            upload_status: 'completed',
            created_at_ms: freshCreatedMs,
          };
        }
        return null;
      },
      isUserBlockedBy: async () => false,
      getUserConversationPrivacyState: async () => ({
        isConversationPrivate: false,
        userMovedToPrivate: false,
        deletedAtMs: null,
      }),
      listConversationMemberIds: async () => ['user-a', 'user-b'],
      isBlockedBetween: async () => false,
    } as unknown as DatabaseClient;

    const storage = {
      createSignedUrl: async (_bucket: string, path: string) =>
        `https://storage.example.test/signed/${path}`,
    } as unknown as StorageClient;

    const env = {
      MEDIA_URL_TTL_SECONDS: '300',
      MEDIA_SIGNING_SECRET: 'test-signing-secret-123456',
      SUPABASE_STORAGE_BUCKET: 'my-chatting-pro-media',
    } as unknown as Env;

    const mediaService = new MediaService(env, db, storage);

    await expect(mediaService.issueSignedDownloadUrl('expired-video', 'user-a')).rejects.toMatchObject({
      statusCode: 404,
      code: 'media_not_found',
    });

    const avatarSigned = await mediaService.issueSignedDownloadUrl('old-avatar', 'user-b');
    expect(avatarSigned.mediaId).toBe('old-avatar');
    expect(avatarSigned.downloadUrl).toContain('old-avatar.jpg');

    const freshSigned = await mediaService.issueSignedDownloadUrl('fresh-image', 'user-b');
    expect(freshSigned.mediaId).toBe('fresh-image');
    expect(freshSigned.downloadUrl).toContain('fresh-image.jpg');
  });
});
