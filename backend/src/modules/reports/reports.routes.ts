import { Hono } from 'hono';
import { Env } from '../../config/env';
import { AppVariables, authMiddleware } from '../../middleware/auth';
import { rateLimitMiddleware } from '../../middleware/rateLimit';
import { ApiError } from '../../middleware/errorHandler';
import { recordAuditLog } from '../../security/audit';

export const reportsRoutes = new Hono<{ Bindings: Env; Variables: AppVariables }>();

reportsRoutes.post(
  '/',
  authMiddleware,
  rateLimitMiddleware({ prefix: 'abuse_report', maxRequests: 10, windowMs: 300_000, keyScope: 'ip_and_user' }),
  async (c) => {
    const auth = c.get('auth');
    const db = c.get('db');
    const body = await c.req.json<{
      targetUserId?: string;
      messageId?: string | null;
      reason?: string;
      details?: string | null;
    }>();

    const targetUserId = body.targetUserId ?? '';
    const reason = (body.reason ?? '').trim().slice(0, 64);
    if (!targetUserId || !reason) {
      throw new ApiError(400, 'invalid_report', 'Target user ID and reason are required.');
    }

    const reportId = crypto.randomUUID();
    await db.insertReport({
      id: reportId,
      reporterId: auth.userId,
      targetUserId,
      messageId: body.messageId ?? null,
      reason,
      details: body.details ? body.details.trim().slice(0, 500) : null,
      createdAtMs: Date.now(),
    });

    await recordAuditLog(db, {
      userId: auth.userId,
      eventType: 'safety.report_submitted',
      outcome: 'success',
      ip: c.req.header('CF-Connecting-IP'),
      meta: { hasMessageId: Boolean(body.messageId) },
    });

    return c.json({ reportId, status: 'open' }, 201);
  }
);
