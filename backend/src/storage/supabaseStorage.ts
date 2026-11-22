import { Env, getIntEnv } from '../config/env';
import { ALLOWED_MEDIA_MIME_TYPES } from '../config/constants';
import { ApiError } from '../middleware/errorHandler';
import { extractExtension } from '../security/validation';

export type MediaCategory = 'profile' | 'image' | 'video' | 'audio' | 'file';

const encoder = new TextEncoder();

export const DEFAULT_SUPABASE_STORAGE_BUCKET = 'my-chatting-pro-media';

export function resolveStorageBucket(env: Pick<Env, 'SUPABASE_STORAGE_BUCKET'>): string {
  return env.SUPABASE_STORAGE_BUCKET?.trim() || DEFAULT_SUPABASE_STORAGE_BUCKET;
}

export function buildStoragePath(
  category: MediaCategory,
  ownerUserId: string,
  conversationId: string | null,
  mediaId: string,
  filename: string
): string {
  const ext = extractExtension(filename);
  if (category === 'profile') {
    return `users/${ownerUserId}/profile/${mediaId}.${ext}`;
  }
  const convId = conversationId ?? 'unassigned';
  switch (category) {
    case 'image':
      return `conversations/${convId}/images/${mediaId}.${ext}`;
    case 'video':
      return `conversations/${convId}/videos/${mediaId}.${ext}`;
    case 'audio':
      return `conversations/${convId}/audio/${mediaId}.${ext}`;
    case 'file':
    default:
      return `conversations/${convId}/files/${mediaId}.${ext}`;
  }
}

export function validateMediaConstraints(
  env: Pick<Env, 'MAX_IMAGE_BYTES' | 'MAX_VIDEO_BYTES' | 'MAX_AUDIO_BYTES' | 'MAX_FILE_BYTES'>,
  category: MediaCategory,
  mimeType: string,
  sizeBytes: number
): { ok: true } | { ok: false; code: 'unsupported_media_type' | 'empty_media_file' | 'media_too_large'; error: string } {
  const allowed = ALLOWED_MEDIA_MIME_TYPES[category];
  const rawMime = mimeType.toLowerCase().split(';')[0]?.trim() ?? '';
  const normalizedMime = rawMime === 'image/jpg' ? 'image/jpeg' : rawMime;
  if (!allowed.includes(normalizedMime)) {
    return {
      ok: false,
      code: 'unsupported_media_type',
      error: `Unsupported MIME type for ${category}: ${normalizedMime || 'unknown'}`,
    };
  }
  if (!Number.isFinite(sizeBytes) || sizeBytes <= 0) {
    return { ok: false, code: 'empty_media_file', error: 'File size must be greater than zero' };
  }

  const maxBytes = (() => {
    switch (category) {
      case 'profile':
      case 'image':
        return getIntEnv(env.MAX_IMAGE_BYTES, 10 * 1024 * 1024);
      case 'video':
        return getIntEnv(env.MAX_VIDEO_BYTES, 50 * 1024 * 1024);
      case 'audio':
        return getIntEnv(env.MAX_AUDIO_BYTES, 15 * 1024 * 1024);
      case 'file':
        return getIntEnv(env.MAX_FILE_BYTES, 50 * 1024 * 1024);
    }
  })();

  if (sizeBytes > maxBytes) {
    return {
      ok: false,
      code: 'media_too_large',
      error: `File exceeds maximum allowed size (${maxBytes} bytes)`,
    };
  }
  return { ok: true };
}

async function signMediaPayload(secret: string, data: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw',
    encoder.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const sig = await crypto.subtle.sign('HMAC', key, encoder.encode(data));
  return Array.from(new Uint8Array(sig))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

export async function createSignedMediaAccessToken(
  env: Pick<Env, 'MEDIA_URL_TTL_SECONDS' | 'MEDIA_SIGNING_SECRET'>,
  mediaId: string,
  viewerUserId: string
): Promise<{ token: string; expiresAtMs: number }> {
  const ttlSec = getIntEnv(env.MEDIA_URL_TTL_SECONDS, 300);
  const expiresAtMs = Date.now() + ttlSec * 1000;
  const payload = `${mediaId}:${viewerUserId}:${expiresAtMs}`;
  const sig = await signMediaPayload(env.MEDIA_SIGNING_SECRET, payload);
  return { token: `${expiresAtMs}.${sig}`, expiresAtMs };
}

export async function verifySignedMediaAccessToken(
  env: Pick<Env, 'MEDIA_SIGNING_SECRET'>,
  mediaId: string,
  viewerUserId: string,
  token: string
): Promise<boolean> {
  const parts = token.split('.');
  if (parts.length !== 2) return false;
  const [expStr, providedSig] = parts;
  if (!expStr || !providedSig) return false;
  const expiresAtMs = Number.parseInt(expStr, 10);
  if (!Number.isFinite(expiresAtMs) || expiresAtMs < Date.now()) return false;

  const expectedSig = await signMediaPayload(env.MEDIA_SIGNING_SECRET, `${mediaId}:${viewerUserId}:${expiresAtMs}`);
  if (expectedSig.length !== providedSig.length) return false;
  let diff = 0;
  for (let i = 0; i < expectedSig.length; i++) {
    diff |= expectedSig.charCodeAt(i) ^ providedSig.charCodeAt(i);
  }
  return diff === 0;
}

export interface StorageClient {
  uploadObject(
    bucket: string,
    objectPath: string,
    bytes: ArrayBuffer,
    mimeType: string
  ): Promise<void>;
  createSignedUrl(
    bucket: string,
    objectPath: string,
    expiresInSeconds: number
  ): Promise<string>;
  downloadObject(
    bucket: string,
    objectPath: string,
    rangeHeader: string | null
  ): Promise<Response>;
  deleteObjects(bucket: string, objectPaths: string[]): Promise<void>;
}

/**
 * Private-bucket Supabase Storage REST client authenticated via SUPABASE_SERVICE_ROLE_KEY.
 */
export class SupabaseStorageClient implements StorageClient {
  private readonly baseUrl: string;

  constructor(
    supabaseUrl: string,
    private readonly serviceRoleKey: string
  ) {
    this.baseUrl = supabaseUrl.trim().replace(/\/+$/, '');
    if (!this.baseUrl || !serviceRoleKey.trim()) {
      throw new Error('SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are required for Supabase Storage.');
    }
  }

  private encodePath(objectPath: string): string {
    return objectPath
      .split('/')
      .filter(Boolean)
      .map((seg) => encodeURIComponent(seg))
      .join('/');
  }

  private authHeaders(extra?: Record<string, string>): HeadersInit {
    const key = this.serviceRoleKey.trim();
    const base: Record<string, string> = {
      apikey: key,
      ...extra,
    };
    if (key.split('.').length === 3) {
      base['Authorization'] = `Bearer ${key}`;
    }
    return base;
  }

  async uploadObject(
    bucket: string,
    objectPath: string,
    bytes: ArrayBuffer,
    mimeType: string
  ): Promise<void> {
    const res = await fetch(
      `${this.baseUrl}/storage/v1/object/${encodeURIComponent(bucket)}/${this.encodePath(objectPath)}`,
      {
        method: 'POST',
        headers: this.authHeaders({
          'Content-Type': mimeType,
          'x-upsert': 'false',
        }),
        body: bytes,
      }
    );
    if (!res.ok) {
      const detail = await res.text().catch(() => '');
      if (res.status === 401 || res.status === 403) {
        throw new ApiError(502, 'storage_permission_denied', 'Supabase Storage rejected upload credentials or bucket policy.');
      }
      if (res.status === 404 || detail.toLowerCase().includes('bucket not found')) {
        throw new ApiError(502, 'storage_bucket_missing', `Supabase Storage bucket "${bucket}" was not found.`);
      }
      if (res.status === 413) {
        throw new ApiError(413, 'media_too_large', 'Uploaded file exceeds storage bucket limit.');
      }
      throw new ApiError(502, 'storage_upload_failed', `Storage upload failed (${res.status}): ${detail.slice(0, 140)}`);
    }
  }

  async createSignedUrl(
    bucket: string,
    objectPath: string,
    expiresInSeconds: number
  ): Promise<string> {
    const res = await fetch(
      `${this.baseUrl}/storage/v1/object/sign/${encodeURIComponent(bucket)}/${this.encodePath(objectPath)}`,
      {
        method: 'POST',
        headers: this.authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ expiresIn: expiresInSeconds }),
      }
    );
    if (!res.ok) {
      const detail = await res.text().catch(() => '');
      if (res.status === 401 || res.status === 403) {
        throw new ApiError(502, 'storage_permission_denied', 'Supabase Storage rejected signed URL permission.');
      }
      if (res.status === 404) {
        throw new ApiError(404, 'media_object_missing', 'Private media file no longer exists in storage.');
      }
      throw new ApiError(502, 'storage_download_failed', `Signed URL creation failed (${res.status}): ${detail.slice(0, 140)}`);
    }
    const payload = (await res.json()) as { signedURL?: string; signedUrl?: string };
    const raw = payload.signedURL ?? payload.signedUrl ?? '';
    if (!raw) {
      throw new ApiError(502, 'storage_download_failed', 'Supabase signed URL response was empty.');
    }
    if (raw.startsWith('https://')) return raw;
    if (raw.startsWith('/storage/v1/')) return `${this.baseUrl}${raw}`;
    if (raw.startsWith('/')) return `${this.baseUrl}/storage/v1${raw}`;
    return `${this.baseUrl}/storage/v1/${raw}`;
  }

  async downloadObject(
    bucket: string,
    objectPath: string,
    rangeHeader: string | null
  ): Promise<Response> {
    const extra: Record<string, string> = {};
    if (rangeHeader) {
      extra['Range'] = rangeHeader;
    }
    return fetch(
      `${this.baseUrl}/storage/v1/object/authenticated/${encodeURIComponent(bucket)}/${this.encodePath(objectPath)}`,
      {
        method: 'GET',
        headers: this.authHeaders(extra),
      }
    );
  }

  async deleteObjects(bucket: string, objectPaths: string[]): Promise<void> {
    if (objectPaths.length === 0) return;
    for (let i = 0; i < objectPaths.length; i += 100) {
      const batch = objectPaths.slice(i, i + 100);
      await fetch(`${this.baseUrl}/storage/v1/object/${encodeURIComponent(bucket)}`, {
        method: 'DELETE',
        headers: this.authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ prefixes: batch }),
      }).catch(() => undefined);
    }
  }
}

export function createSupabaseStorage(env: Pick<Env, 'SUPABASE_URL' | 'SUPABASE_SERVICE_ROLE_KEY'>): SupabaseStorageClient {
  return new SupabaseStorageClient(env.SUPABASE_URL, env.SUPABASE_SERVICE_ROLE_KEY);
}
