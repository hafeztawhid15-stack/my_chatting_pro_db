import { Env, getIntEnv } from '../../config/env';
import { DatabaseClient } from '../../database/supabase';
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
      const convService = new ConversationsService(this.db);
      await convService.assertMember(input.conversationId, input.userId);
    }

    const validation = validateMediaConstraints(
      this.env,
      input.category,
      input.mimeType,
      input.bytes.byteLength
    );
    if (!validation.ok) {
      throw new ApiError(400, 'invalid_media', validation.error);
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
  async issueSignedDownloadUrl(mediaId: string, viewerUserId: string) {
    const media = await this.assertMediaAccess(mediaId, viewerUserId);
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
    rangeHeader: string | null
  ): Promise<Response> {
    if (signedToken) {
      const ok = await verifySignedMediaAccessToken(this.env, mediaId, viewerUserId, signedToken);
      if (!ok) {
        throw new ApiError(403, 'invalid_media_token', 'Signed media access token is invalid or expired.');
      }
    }

    const media = await this.assertMediaAccess(mediaId, viewerUserId);
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

  private async assertMediaAccess(mediaId: string, viewerUserId: string) {
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

    if (media.owner_user_id === viewerUserId) {
      return media;
    }

    if (!media.conversation_id) {
      throw new ApiError(403, 'forbidden_media', 'Access denied.');
    }

    const members = await this.db.listConversationMemberIds(media.conversation_id);
    if (!members.includes(viewerUserId)) {
      throw new ApiError(403, 'forbidden_media', 'You are not authorized to access this conversation media.');
    }
    return media;
  }
}
