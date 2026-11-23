import { Env } from '../../config/env';
import { DatabaseClient } from '../../database/supabase';
import { ApiError } from '../../middleware/errorHandler';
import { recordAuditLog } from '../../security/audit';
import { sanitizeAboutText, sanitizeDisplayName } from '../../security/validation';
import { resolveStorageBucket, StorageClient } from '../../storage/supabaseStorage';
import { computeIsFreshlyOnline } from '../../utils/presence';
import { verifyPin } from '../auth/pin.crypto';
import { dispatchSecurityAlert } from '../notifications/pushDispatcher';

export class ProfilesService {
  constructor(
    private readonly env: Env,
    private readonly db: DatabaseClient,
    private readonly storage?: StorageClient
  ) {}

  async getOwnProfile(userId: string) {
    const row = await this.db.getOwnProfileDetail(userId);
    if (!row) {
      throw new ApiError(404, 'profile_not_found', 'Profile not found.');
    }
    return {
      id: row.id,
      phoneE164: row.phone_e164,
      displayName: row.display_name,
      about: row.about_text,
      avatarMediaId: row.avatar_media_id,
      isOnline: computeIsFreshlyOnline(row.is_online, row.last_seen_ms),
      lastSeenMs: row.last_seen_ms,
    };
  }

  async updateOwnProfile(userId: string, input: { displayName?: string; about?: string; avatarMediaId?: string | null }) {
    const current = await this.getOwnProfile(userId);
    const nextName =
      input.displayName !== undefined
        ? sanitizeDisplayName(input.displayName)
        : current.displayName;
    if (!nextName) {
      throw new ApiError(400, 'invalid_display_name', 'Display name must be 1 to 60 characters.');
    }
    const nextAbout = input.about !== undefined ? sanitizeAboutText(input.about) : current.about;

    let nextAvatarId = current.avatarMediaId;
    if (input.avatarMediaId !== undefined) {
      if (input.avatarMediaId === null) {
        nextAvatarId = null;
      } else {
        const media = await this.db.findCompletedProfileAvatarMedia(input.avatarMediaId, userId);
        if (!media) {
          throw new ApiError(403, 'invalid_avatar_media', 'Profile photo does not belong to this user.');
        }
        nextAvatarId = media.id;
      }
    }

    await this.db.updateOwnProfile(userId, nextName, nextAbout, nextAvatarId, Date.now());
    return this.getOwnProfile(userId);
  }

  /**
   * Full GDPR/Play-compliant Account Deletion:
   * 1. Verifies PIN before destructive deletion.
   * 2. Deletes all private Supabase Storage objects owned by user.
   * 3. Atomically revokes sessions and deletes user, profile, devices, blocks, and media metadata in Supabase PostgreSQL.
   */
  async deleteAccountPermanently(
    userId: string,
    pin: string,
    confirmText: string,
    clientIp?: string | null
  ) {
    if (confirmText.trim() !== 'DELETE') {
      throw new ApiError(
        400,
        'invalid_delete_confirmation',
        'Please type DELETE in uppercase to confirm permanent account deletion.'
      );
    }

    const user = await this.db.findActiveUserPinHash(userId);
    if (!user) {
      throw new ApiError(404, 'account_not_found', 'Account not found.');
    }
    const pinValid = await verifyPin(pin, user.pin_hash, this.env.PIN_PEPPER);
    if (!pinValid) {
      throw new ApiError(401, 'wrong_pin', 'Incorrect PIN. Account deletion aborted.');
    }

    await dispatchSecurityAlert(this.env, this.db, userId, 'account_deleted');

    const userMedia = await this.db.listUserMediaPaths(userId);
    if (this.storage && userMedia.length > 0) {
      const byBucket = new Map<string, string[]>();
      const defaultBucket = resolveStorageBucket(this.env);
      for (const item of userMedia) {
        const bucket = item.storage_bucket || defaultBucket;
        const list = byBucket.get(bucket) ?? [];
        list.push(item.storage_path);
        byBucket.set(bucket, list);
      }
      for (const [bucket, paths] of byBucket.entries()) {
        await this.storage.deleteObjects(bucket, paths);
      }
    }

    const now = Date.now();
    await this.db.deleteUserAccountTx(userId, now);

    await recordAuditLog(this.db, {
      userId: null,
      eventType: 'account.deleted_permanently',
      outcome: 'success',
      ip: clientIp,
      meta: { removedMediaCount: userMedia.length },
    });

    return {
      deleted: true,
      deletedAtMs: now,
      removedMediaObjects: userMedia.length,
      retentionNotice:
        'Your account, profile, sessions, and private Supabase Storage media have been permanently deleted. Minimal anonymized security audit metadata may be retained for abuse prevention.',
    };
  }
}
