import { Hono } from 'hono';
import { Env } from '../../config/env';
import { UniqueViolationError } from '../../database/supabase';
import { AppVariables, authMiddleware } from '../../middleware/auth';
import { rateLimitMiddleware } from '../../middleware/rateLimit';
import { ApiError } from '../../middleware/errorHandler';
import { recordAuditLog } from '../../security/audit';

export const reportsRoutes = new Hono<{ Bindings: Env; Variables: AppVariables }>();

const ALLOWED_REPORT_REASONS: Record<string, string> = {
  spam: 'Spam',
  abuse: 'Abuse',
  harassment: 'Harassment',
  other: 'Other',
};

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

    const targetUserId = (body.targetUserId ?? '').trim();
    const rawReason = (body.reason ?? '').trim().slice(0, 64);
    const normalizedReason = ALLOWED_REPORT_REASONS[rawReason.toLowerCase()] ?? (rawReason ? 'Other' : '');

    if (!targetUserId || !normalizedReason) {
      throw new ApiError(400, 'invalid_report', 'Target user ID and report reason are required.');
    }
    if (targetUserId === auth.userId) {
      throw new ApiError(400, 'invalid_report', 'You cannot report your own account.');
    }

    const reportId = crypto.randomUUID();
    try {
      await db.insertReport({
        id: reportId,
        reporterId: auth.userId,
        targetUserId,
        messageId: body.messageId ?? null,
        reason: normalizedReason,
        details: body.details ? body.details.trim().slice(0, 500) : null,
        createdAtMs: Date.now(),
      });
    } catch (err) {
      if (err instanceof UniqueViolationError) {
        throw new ApiError(
          409,
          'duplicate_report',
          'You already have an active open report for this user under the same category.'
        );
      }
      throw err;
    }

    await recordAuditLog(db, {
      userId: auth.userId,
      eventType: 'safety.report_submitted',
      outcome: 'success',
      ip: c.req.header('CF-Connecting-IP'),
      meta: { hasMessageId: Boolean(body.messageId), reason: normalizedReason },
    });

    return c.json({ reportId, status: 'open', reason: normalizedReason }, 201);
  }
);
