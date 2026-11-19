import { Hono } from 'hono';
import { Env } from '../../config/env';
import { ApiError } from '../../middleware/errorHandler';
import { AppVariables, authMiddleware } from '../../middleware/auth';
import { rateLimitMiddleware } from '../../middleware/rateLimit';
import { recordAuditLog } from '../../security/audit';
import { sha256Hex, timingSafeEqualBytes } from '../../security/jwt';

const encoder = new TextEncoder();

export const ownerRoutes = new Hono<{ Bindings: Env; Variables: AppVariables }>();

function maskPhone(phoneE164: string): string {
  if (phoneE164.length <= 6) return '••••••';
  const prefix = phoneE164.slice(0, 4);
  const suffix = phoneE164.slice(-4);
  return `${prefix}••••${suffix}`;
}

function parseOwnerAllowlist(raw: string | undefined): string[] {
  if (!raw) return [];
  return raw
    .split(',')
    .map((id) => id.trim())
    .filter(Boolean);
}

/**
 * Owner-only App Usage & Active Users endpoint.
 * Defense-in-depth authorization:
 * 1. Valid authenticated JWT & active Supabase session (`authMiddleware`)
 * 2. IP + User scoped sliding-window rate limit
 * 3. Server-side Owner Identity Verification (`users.role = 'owner'` OR `OWNER_USER_IDS` allowlist)
 * 4. Constant-time `X-Owner-Key` secret verification (`OWNER_MONITOR_KEY`)
 * 5. Mandatory audit logging & masked phone output — never exposes PIN hashes or message contents.
 */
ownerRoutes.get(
  '/activity',
  authMiddleware,
  rateLimitMiddleware({
    prefix: 'owner_activity',
    maxRequests: 25,
    windowMs: 60_000,
    blockMs: 300_000,
    keyScope: 'ip_and_user',
  }),
  async (c) => {
    const auth = c.get('auth');
    const db = c.get('db');
    const clientIp = c.req.header('CF-Connecting-IP');

    const allowlist = parseOwnerAllowlist(c.env.OWNER_USER_IDS);
    const isAuthorizedOwner = auth.userRole === 'owner' || allowlist.includes(auth.userId);
    if (!isAuthorizedOwner) {
      await recordAuditLog(db, {
        userId: auth.userId,
        eventType: 'owner.activity_forbidden_non_owner',
        outcome: 'blocked',
        ip: clientIp,
      });
      throw new ApiError(
        403,
        'owner_access_denied',
        'Access denied. Only verified owner accounts can view Owner Monitor.'
      );
    }

    const configuredKey = c.env.OWNER_MONITOR_KEY?.trim() ?? '';
    if (!configuredKey) {
      throw new ApiError(
        503,
        'owner_monitor_not_configured',
        'OWNER_MONITOR_KEY secret is not configured on Cloudflare Worker.'
      );
    }

    const providedKey = c.req.header('X-Owner-Key')?.trim() ?? '';
    if (!providedKey) {
      await recordAuditLog(db, {
        userId: auth.userId,
        eventType: 'owner.activity_missing_key',
        outcome: 'blocked',
        ip: clientIp,
      });
      throw new ApiError(403, 'invalid_owner_key', 'Owner Monitor Key is required.');
    }

    const [expectedHash, providedHash] = await Promise.all([
      sha256Hex(configuredKey),
      sha256Hex(providedKey),
    ]);

    const match = timingSafeEqualBytes(encoder.encode(expectedHash), encoder.encode(providedHash));
    if (!match) {
      await recordAuditLog(db, {
        userId: auth.userId,
        eventType: 'owner.activity_denied',
        outcome: 'blocked',
        ip: clientIp,
      });
      throw new ApiError(403, 'invalid_owner_key', 'Incorrect Owner Monitor Key.');
    }

    const rawSearch = (c.req.query('q') ?? '').trim().slice(0, 64);
    const pageRaw = Number.parseInt(c.req.query('page') ?? '1', 10);
    const limitRaw = Number.parseInt(c.req.query('limit') ?? '20', 10);
    const page = Number.isFinite(pageRaw) && pageRaw >= 1 ? pageRaw : 1;
    const limit = Number.isFinite(limitRaw) ? Math.min(Math.max(limitRaw, 1), 50) : 20;
    const offset = (page - 1) * limit;

    const now = Date.now();
    const last24hCutoff = now - 24 * 60 * 60 * 1000;

    const snapshot = await db.getOwnerActivitySnapshot({
      search: rawSearch.length > 0 ? rawSearch : null,
      limit,
      offset,
      last24hCutoff,
      nowMs: now,
    });

    await recordAuditLog(db, {
      userId: auth.userId,
      eventType: 'owner.activity_viewed',
      outcome: 'success',
      ip: clientIp,
      meta: { page, limit, hasSearch: rawSearch.length > 0 },
    });

    const mappedUsers = snapshot.users.map((row) => ({
      userId: row.user_id,
      displayName: row.display_name,
      countryIso2: row.country_iso2,
      maskedPhone: maskPhone(row.phone_e164),
      isOnline: Boolean(row.is_online),
      lastSeenMs: row.last_seen_ms,
      createdAtMs: row.created_at_ms,
      latestDeviceName: row.latest_device_name ?? 'Android Device',
      latestPlatform: row.latest_platform ?? 'android',
      latestAppVersion: row.latest_app_version ?? '1.0.0',
      activeSessionCount: row.active_session_count,
    }));

    return c.json({
      summary: {
        totalRegisteredUsers: snapshot.summary.total_registered_users ?? 0,
        currentlyOnlineUsers: snapshot.summary.currently_online_users ?? 0,
        activeLast24hUsers: snapshot.summary.active_last_24h_users ?? 0,
        activeSessionsCount: snapshot.summary.active_sessions_count ?? 0,
        generatedAtMs: now,
      },
      pagination: {
        page,
        limit,
        totalCount: snapshot.total_count ?? mappedUsers.length,
        hasMore: offset + mappedUsers.length < (snapshot.total_count ?? 0),
      },
      users: mappedUsers,
    });
  }
);
