import { Context, Next } from 'hono';
import { Env } from '../config/env';
import { createSupabaseDb } from '../database/supabase';
import { sha256Hex } from '../security/jwt';
import { AppVariables } from './auth';

export function rateLimitMiddleware(options: {
  prefix: string;
  maxRequests: number;
  windowMs: number;
  blockMs?: number;
  keyScope?: 'ip' | 'user' | 'ip_and_user';
}) {
  return async (c: Context<{ Bindings: Env; Variables: Partial<AppVariables> }>, next: Next) => {
    const ip = c.req.header('CF-Connecting-IP') ?? c.req.header('X-Forwarded-For') ?? 'unknown';
    const ipHash = (await sha256Hex(ip)).slice(0, 16);
    const authUserId = c.get('auth')?.userId;
    const scope = options.keyScope ?? 'ip';

    let subjectKey = ipHash;
    if (scope === 'user' && authUserId) {
      subjectKey = authUserId;
    } else if (scope === 'ip_and_user') {
      subjectKey = `${ipHash}:${authUserId ?? 'anon'}`;
    }

    const bucketKey = `${options.prefix}:${subjectKey}`;
    const db = c.get('db') ?? createSupabaseDb(c.env);
    const result = await db.consumeRateLimit(
      bucketKey,
      options.maxRequests,
      options.windowMs,
      options.blockMs ?? options.windowMs,
      Date.now()
    );

    if (!result.allowed) {
      const retryAfterSec = Math.max(1, result.retryAfterSeconds);
      c.header('Retry-After', String(retryAfterSec));
      return c.json(
        {
          error: 'rate_limited',
          retryAfterSeconds: retryAfterSec,
          message: 'Too many requests. Please wait before retrying.',
        },
        429
      );
    }

    await next();
  };
}
