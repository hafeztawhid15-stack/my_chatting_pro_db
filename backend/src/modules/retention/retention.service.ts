import { AUTO_DELETE_RETENTION_MS } from '../../config/constants';
import { Env } from '../../config/env';
import { createSupabaseDb, DatabaseClient } from '../../database/supabase';
import { createSupabaseStorage, StorageClient } from '../../storage/supabaseStorage';

export interface RetentionCleanupResult {
  deletedStorageFiles: number;
  deletedMessages: number;
  deletedCalls: number;
  deletedMediaRows: number;
}

const DEFAULT_THROTTLE_MS = 6 * 60 * 60 * 1000;
let lastCleanupRunAtMs = 0;
let inFlightCleanup: Promise<RetentionCleanupResult> | null = null;

export function computeRetentionCutoffMs(nowMs = Date.now()): number {
  return Math.max(0, Math.trunc(nowMs) - AUTO_DELETE_RETENTION_MS);
}

/**
 * Deletes storage objects older than 20 days (excluding profile avatars) BEFORE
 * invoking the atomic PostgreSQL retention purge RPC, so storage paths are never lost.
 */
export async function runRetentionCleanup(
  db: Pick<DatabaseClient, 'listExpiredNonProfileMediaObjects' | 'purgeExpiredRetentionData'>,
  storage: Pick<StorageClient, 'deleteObjects'>,
  nowMs = Date.now(),
  options?: { maxBatches?: number; batchSize?: number }
): Promise<RetentionCleanupResult> {
  const cutoffMs = computeRetentionCutoffMs(nowMs);
  const maxBatches = Math.max(1, options?.maxBatches ?? 8);
  const batchSize = Math.max(1, Math.min(options?.batchSize ?? 100, 200));

  let deletedStorageFiles = 0;
  let deletedMessages = 0;
  let deletedCalls = 0;
  let deletedMediaRows = 0;

  for (let batchIdx = 0; batchIdx < maxBatches; batchIdx += 1) {
    const expiredMedia = await db.listExpiredNonProfileMediaObjects(cutoffMs, batchSize);

    const byBucket = new Map<string, Array<{ id: string; path: string }>>();
    for (const item of expiredMedia) {
      if (!item.storage_bucket || !item.storage_path) continue;
      const list = byBucket.get(item.storage_bucket) ?? [];
      list.push({ id: item.id, path: item.storage_path });
      byBucket.set(item.storage_bucket, list);
    }

    const purgedMediaIds: string[] = [];
    for (const [bucket, items] of byBucket.entries()) {
      await storage.deleteObjects(
        bucket,
        items.map((item) => item.path)
      );
      deletedStorageFiles += items.length;
      for (const item of items) {
        purgedMediaIds.push(item.id);
      }
    }

    const dbRes = await db.purgeExpiredRetentionData(cutoffMs, purgedMediaIds);
    deletedMessages += dbRes.deletedMessages;
    deletedCalls += dbRes.deletedCalls;
    deletedMediaRows += dbRes.deletedMediaRows;

    if (expiredMedia.length < batchSize) {
      break;
    }
  }

  return {
    deletedStorageFiles,
    deletedMessages,
    deletedCalls,
    deletedMediaRows,
  };
}

export async function runScheduledRetentionCleanup(
  env: Env,
  nowMs = Date.now()
): Promise<RetentionCleanupResult> {
  const db = createSupabaseDb(env);
  const storage = createSupabaseStorage(env);
  return runRetentionCleanup(db, storage, nowMs, { maxBatches: 12, batchSize: 100 });
}

export function maybeRunThrottledRetentionCleanup(
  env: Env,
  minIntervalMs = DEFAULT_THROTTLE_MS,
  nowMs = Date.now()
): Promise<RetentionCleanupResult | null> {
  if (inFlightCleanup) {
    return inFlightCleanup;
  }
  if (lastCleanupRunAtMs > 0 && nowMs - lastCleanupRunAtMs < minIntervalMs) {
    return Promise.resolve(null);
  }
  lastCleanupRunAtMs = nowMs;
  const db = createSupabaseDb(env);
  const storage = createSupabaseStorage(env);
  const task = runRetentionCleanup(db, storage, nowMs, { maxBatches: 4, batchSize: 100 })
    .catch(() => ({
      deletedStorageFiles: 0,
      deletedMessages: 0,
      deletedCalls: 0,
      deletedMediaRows: 0,
    }))
    .finally(() => {
      inFlightCleanup = null;
    });
  inFlightCleanup = task;
  return task;
}

export function resetRetentionThrottleForTests(): void {
  lastCleanupRunAtMs = 0;
  inFlightCleanup = null;
}
