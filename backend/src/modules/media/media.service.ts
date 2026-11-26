import { AUTO_DELETE_RETENTION_MS } from '../../config/constants';
import { Env, getIntEnv } from '../../config/env';
import { DatabaseClient } from '../../database/supabase';
import { verifyPrivateUnlockToken } from '../../middleware/auth';
import { ApiError } from '../../middleware/errorHandler';
import { sanitizeFilename } from '../../security/validation';
import {
  buildStoragePath,
  createSignedMediaAccessToken,
  MediaCategory,
  resolveStorageBucket,
  StorageClient,
  validateMediaConstraints,
  verifySignedMediaAccessToken,
} from '../../storage/supabaseStorage';
import { ConversationsService } from '../conversations/conversations.service';

export class MediaService {
  constructor(
    private readonly env: Env,
    private readonly db: DatabaseClient,
    private readonly storage: StorageClient
  ) {}

  async uploadMedia(input: {
    userId: string;
    sessionId?: string;
    privateUnlockToken?: string | null;
    category: MediaCategory;
    conversationId: string | null;
    filename: string;
    mimeType: string;
    bytes: ArrayBuffer;
    durationMs?: number | null;
    width?: number | null;
    height?: number | null;
  }) {
    if (input.category !== 'profile') {
      if (!input.conversationId) {
        throw new ApiError(400, 'missing_conversation_id', 'Conversation ID is required for chat media.');
      }
      const privacy = await this.db.getUserConversationPrivacyState(input.conversationId, input.userId);
      if (!privacy) {
        throw new ApiError(404, 'conversation_not_found', 'Conversation not found.');
      }
      const effectivePrivate = privacy.isConversationPrivate || privacy.userMovedToPrivate;
      if (effectivePrivate) {
        const unlocked = await verifyPrivateUnlockToken(input.privateUnlockToken, this.env, this.db, {
          userId: input.userId,
          sessionId: input.sessionId,
        });
        if (!unlocked) {
          throw new ApiError(403, 'private_chat_locked', 'Private Chat PIN verification is required.');
        }
      }
      const convService = new ConversationsService(this.db);
      const memberIds = await convService.assertMember(input.conversationId, input.userId, effectivePrivate);
      for (const peerId of memberIds) {
        if (peerId === input.userId) continue;
        if (await this.db.isBlockedBetween(input.userId, peerId)) {
          throw new ApiError(
            403,
            'user_blocked',
            'Media upload is unavailable because a block exists between users.'
          );
        }
      }
    }

    const validation = validateMediaConstraints(
      this.env,
      input.category,
      input.mimeType,
      input.bytes.byteLength
    );
    if (!validation.ok) {
      const status = validation.code === 'media_too_large' ? 413 : 400;
      throw new ApiError(status, validation.code, validation.error);
    }

    const mediaId = crypto.randomUUID();
    const safeName = sanitizeFilename(input.filename);
    const bucket = resolveStorageBucket(this.env);
    const storagePath = buildStoragePath(input.category, input.userId, input.conversationId, mediaId, safeName);
    const now = Date.now();

    await this.storage.uploadObject(bucket, storagePath, input.bytes, input.mimeType);

    try {
      await this.db.insertMediaObject({
        id: mediaId,
        ownerUserId: input.userId,
        conversationId: input.conversationId,
        category: input.category,
        storageBucket: bucket,
        storagePath,
        originalFilename: safeName,
        mimeType: input.mimeType,
        sizeBytes: input.bytes.byteLength,
        durationMs: input.durationMs ?? null,
        width: input.width ?? null,
        height: input.height ?? null,
        createdAtMs: now,
      });
    } catch (err) {
      await this.storage.deleteObjects(bucket, [storagePath]);
      throw err;
    }

    return {
      id: mediaId,
      category: input.category,
      originalFilename: safeName,
      mimeType: input.mimeType,
      sizeBytes: input.bytes.byteLength,
      durationMs: input.durationMs ?? null,
      width: input.width ?? null,
      height: input.height ?? null,
    };
  }

  /**
   * Generates a short-lived private Supabase Storage signed download URL after verifying
   * conversation/block authorization.
   */
  async issueSignedDownloadUrl(
    mediaId: string,
    viewerUserId: string,
    options?: { sessionId?: string; privateUnlockToken?: string | null }
  ) {
    const media = await this.assertMediaAccess(mediaId, viewerUserId, options);
    const ttlSec = getIntEnv(this.env.MEDIA_URL_TTL_SECONDS, 300);
    const bucket = media.storage_bucket || resolveStorageBucket(this.env);

    const [signedTokenObj, downloadUrl] = await Promise.all([
      createSignedMediaAccessToken(this.env, media.id, viewerUserId),
      this.storage.createSignedUrl(bucket, media.storage_path, ttlSec),
    ]);

    return {
      mediaId: media.id,
      signedToken: signedTokenObj.token,
      expiresAtMs: signedTokenObj.expiresAtMs,
      mimeType: media.mime_type,
      originalFilename: media.original_filename,
      sizeBytes: media.size_bytes,
      downloadUrl,
    };
  }

  async streamMedia(
    mediaId: string,
    viewerUserId: string,
    signedToken: string | undefined,
    rangeHeader: string | null,
    options?: { sessionId?: string; privateUnlockToken?: string | null }
  ): Promise<Response> {
    if (signedToken) {
      const ok = await verifySignedMediaAccessToken(this.env, mediaId, viewerUserId, signedToken);
      if (!ok) {
        throw new ApiError(403, 'invalid_media_token', 'Signed media access token is invalid or expired.');
      }
    }

    const media = await this.assertMediaAccess(mediaId, viewerUserId, options);
    const bucket = media.storage_bucket || resolveStorageBucket(this.env);
    const upstream = await this.storage.downloadObject(bucket, media.storage_path, rangeHeader);

    if (!upstream.ok && upstream.status !== 206) {
      throw new ApiError(404, 'media_object_missing', 'Private media object not found in storage.');
    }

    const headers = new Headers();
    headers.set('Content-Type', media.mime_type);
    headers.set('Content-Disposition', `inline; filename="${media.original_filename}"`);
    headers.set('Cache-Control', 'private, max-age=300');
    headers.set('Accept-Ranges', 'bytes');

    const contentRange = upstream.headers.get('Content-Range');
    if (contentRange) headers.set('Content-Range', contentRange);
    const contentLength = upstream.headers.get('Content-Length');
    if (contentLength) headers.set('Content-Length', contentLength);

    return new Response(upstream.body, {
      status: upstream.status === 206 ? 206 : 200,
      headers,
    });
  }

  private async assertMediaAccess(
    mediaId: string,
    viewerUserId: string,
    options?: { sessionId?: string; privateUnlockToken?: string | null }
  ) {
    const media = await this.db.findMediaObjectById(mediaId);
    if (!media || media.upload_status !== 'completed') {
      throw new ApiError(404, 'media_not_found', 'Media not found.');
    }

    if (media.category === 'profile') {
      if (media.owner_user_id !== viewerUserId) {
        const blocked = await this.db.isUserBlockedBy(media.owner_user_id, viewerUserId);
        if (blocked) {
          throw new ApiError(403, 'forbidden_media', 'Access denied.');
        }
      }
      return media;
    }

    if (
      typeof media.created_at_ms === 'number' &&
      media.created_at_ms > 0 &&
      Date.now() - media.created_at_ms >= AUTO_DELETE_RETENTION_MS
    ) {
      throw new ApiError(404, 'media_not_found', 'Media expired and was removed after 20 days.');
    }

    if (!media.conversation_id) {
      if (media.owner_user_id === viewerUserId) return media;
      throw new ApiError(403, 'forbidden_media', 'Access denied.');
    }

    const privacy = await this.db.getUserConversationPrivacyState(media.conversation_id, viewerUserId);
    const effectivePrivate = Boolean(
      privacy && (privacy.isConversationPrivate || privacy.userMovedToPrivate)
    );
    if (effectivePrivate) {
      const unlocked = await verifyPrivateUnlockToken(options?.privateUnlockToken, this.env, this.db, {
        userId: viewerUserId,
        sessionId: options?.sessionId,
      });
      if (!unlocked) {
        throw new ApiError(403, 'private_chat_locked', 'Private Chat PIN verification is required.');
      }
    }

    const members = await this.db.listConversationMemberIds(media.conversation_id);
    if (!members.includes(viewerUserId)) {
      throw new ApiError(403, 'forbidden_media', 'You are not authorized to access this conversation media.');
    }

    if (media.owner_user_id === viewerUserId) {
      return media;
    }

    if (await this.db.isBlockedBetween(viewerUserId, media.owner_user_id)) {
      throw new ApiError(
        403,
        'user_blocked',
        'Media access is unavailable because a block exists between users.'
      );
    }
    return media;
  }
}
