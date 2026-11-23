import { Hono } from 'hono';
import { Env } from '../../config/env';
import { createSupabaseDb } from '../../database/supabase';
import { AppVariables, authMiddleware } from '../../middleware/auth';
import { ApiError } from '../../middleware/errorHandler';
import { rateLimitMiddleware } from '../../middleware/rateLimit';
import { verifyJwt } from '../../security/jwt';
import { createSupabaseStorage, MediaCategory } from '../../storage/supabaseStorage';
import { MediaService } from './media.service';

export const mediaRoutes = new Hono<{ Bindings: Env; Variables: AppVariables }>();

interface UploadedFileLike {
  name?: string;
  type?: string;
  arrayBuffer: () => Promise<ArrayBuffer>;
}

function resolveEffectiveMimeType(fileType: string | undefined, filename: string, category: MediaCategory): string {
  const cleaned = (fileType ?? '').toLowerCase().split(';')[0]?.trim() ?? '';
  if (cleaned && cleaned !== 'application/octet-stream') {
    return cleaned === 'image/jpg' ? 'image/jpeg' : cleaned;
  }
  const ext = filename.split('.').pop()?.toLowerCase() ?? '';
  const byExt: Record<string, string> = {
    jpg: 'image/jpeg',
    jpeg: 'image/jpeg',
    png: 'image/png',
    webp: 'image/webp',
    gif: 'image/gif',
    mp4: category === 'audio' ? 'audio/mp4' : 'video/mp4',
    mov: 'video/quicktime',
    m4a: 'audio/mp4',
    aac: 'audio/aac',
    mp3: 'audio/mpeg',
    ogg: 'audio/ogg',
    opus: 'audio/opus',
    wav: 'audio/wav',
    webm: category === 'audio' ? 'audio/webm' : 'video/webm',
    amr: 'audio/amr',
    '3gp': category === 'audio' ? 'audio/3gpp' : 'video/3gpp',
    pdf: 'application/pdf',
    txt: 'text/plain',
    csv: 'text/csv',
    json: 'application/json',
    zip: 'application/zip',
  };
  return byExt[ext] ?? (cleaned || 'application/octet-stream');
}

mediaRoutes.post(
  '/upload',
  authMiddleware,
  rateLimitMiddleware({ prefix: 'media_upload', maxRequests: 30, windowMs: 60_000, keyScope: 'ip_and_user' }),
  async (c) => {
    const auth = c.get('auth');
    const formData = await c.req.formData();
    const rawFile = formData.get('file') as unknown;
    const file =
      rawFile &&
      typeof rawFile === 'object' &&
      typeof (rawFile as Partial<UploadedFileLike>).arrayBuffer === 'function'
        ? (rawFile as UploadedFileLike)
        : null;
    if (!file) {
      throw new ApiError(400, 'missing_file', 'Multipart field "file" is required.');
    }

    const categoryRaw = String(formData.get('category') ?? 'file');
    const validCategories: MediaCategory[] = ['profile', 'image', 'video', 'audio', 'file'];
    const category = validCategories.includes(categoryRaw as MediaCategory)
      ? (categoryRaw as MediaCategory)
      : 'file';

    const conversationIdRaw = formData.get('conversationId');
    const conversationId = typeof conversationIdRaw === 'string' && conversationIdRaw.length > 0 ? conversationIdRaw : null;

    const durationMsRaw = formData.get('durationMs');
    const durationMs = typeof durationMsRaw === 'string' ? Number.parseInt(durationMsRaw, 10) : null;

    const widthRaw = formData.get('width');
    const width = typeof widthRaw === 'string' ? Number.parseInt(widthRaw, 10) : null;

    const heightRaw = formData.get('height');
    const height = typeof heightRaw === 'string' ? Number.parseInt(heightRaw, 10) : null;

    const filename = file.name || (category === 'audio' ? 'voice.m4a' : category === 'image' || category === 'profile' ? 'photo.jpg' : 'upload.bin');
    const mimeType = resolveEffectiveMimeType(file.type, filename, category);
    const bytes = await file.arrayBuffer();
    const service = new MediaService(c.env, c.get('db'), createSupabaseStorage(c.env));
    const uploaded = await service.uploadMedia({
      userId: auth.userId,
      sessionId: auth.sessionId,
      privateUnlockToken: c.req.header('X-Private-Unlock-Token'),
      category,
      conversationId,
      filename,
      mimeType,
      bytes,
      durationMs: durationMs && Number.isFinite(durationMs) ? durationMs : null,
      width: width && Number.isFinite(width) ? width : null,
      height: height && Number.isFinite(height) ? height : null,
    });

    return c.json({ media: uploaded }, 201);
  }
);

mediaRoutes.get('/:mediaId/signed-url', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const mediaId = c.req.param('mediaId') ?? '';
  const service = new MediaService(c.env, c.get('db'), createSupabaseStorage(c.env));
  const result = await service.issueSignedDownloadUrl(mediaId, auth.userId, {
    sessionId: auth.sessionId,
    privateUnlockToken: c.req.header('X-Private-Unlock-Token'),
  });
  return c.json(result);
});

mediaRoutes.get('/:mediaId/content', async (c) => {
  const mediaId = c.req.param('mediaId') ?? '';
  const token = c.req.query('token');
  const uidQuery = c.req.query('uid');
  const db = createSupabaseDb(c.env);
  const service = new MediaService(c.env, db, createSupabaseStorage(c.env));

  // Supports either active Bearer token OR short-lived HMAC signed token
  const authHeader = c.req.header('Authorization');
  if (authHeader && authHeader.startsWith('Bearer ')) {
    const payload = await verifyJwt(authHeader.slice(7).trim(), c.env.JWT_ACCESS_SECRET, 'access');
    if (!payload) {
      return c.json({ error: 'unauthorized', message: 'Invalid bearer token.' }, 401);
    }
    const session = await db.verifyActiveSession(payload.sid, payload.sub);
    if (!session || session.revoked_at_ms !== null || session.account_status !== 'active') {
      return c.json({ error: 'session_revoked', message: 'Session is no longer active.' }, 401);
    }
    return service.streamMedia(mediaId, payload.sub, undefined, c.req.header('Range') ?? null, {
      sessionId: payload.sid,
      privateUnlockToken: c.req.header('X-Private-Unlock-Token'),
    });
  }

  if (!token || !uidQuery) {
    return c.json({ error: 'unauthorized', message: 'Missing authorization or signed token.' }, 401);
  }

  return service.streamMedia(mediaId, uidQuery, token, c.req.header('Range') ?? null, {
    privateUnlockToken: c.req.header('X-Private-Unlock-Token'),
  });
});
