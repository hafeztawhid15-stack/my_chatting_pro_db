export interface Env {
  USER_COORDINATOR: DurableObjectNamespace;
  CONVERSATION_COORDINATOR: DurableObjectNamespace;

  ENVIRONMENT: string;
  SUPABASE_STORAGE_BUCKET?: string;
  ACCESS_TOKEN_TTL_SECONDS: string;
  REFRESH_TOKEN_TTL_SECONDS: string;
  MEDIA_URL_TTL_SECONDS: string;
  MAX_IMAGE_BYTES: string;
  MAX_VIDEO_BYTES: string;
  MAX_AUDIO_BYTES: string;
  MAX_FILE_BYTES: string;
  ALLOWED_STUN_URL: string;

  // Worker Secrets (stored via `wrangler secret put` or `.dev.vars`, never in client APK)
  SUPABASE_URL: string;
  SUPABASE_SERVICE_ROLE_KEY: string;
  JWT_ACCESS_SECRET: string;
  JWT_REFRESH_SECRET: string;
  PIN_PEPPER: string;
  MEDIA_SIGNING_SECRET: string;
  OWNER_MONITOR_KEY?: string;
  OWNER_USER_IDS?: string;

  // Optional dedicated TURN server credentials
  CLOUDFLARE_TURN_TOKEN_ID?: string;
  CLOUDFLARE_TURN_API_TOKEN?: string;
  STATIC_TURN_URLS?: string;
  STATIC_TURN_USERNAME?: string;
  STATIC_TURN_CREDENTIAL?: string;
}

export interface AuthContext {
  userId: string;
  deviceId: string;
  sessionId: string;
  userRole: 'user' | 'owner';
}

export function getIntEnv(value: string | undefined, fallback: number): number {
  if (!value) return fallback;
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}
