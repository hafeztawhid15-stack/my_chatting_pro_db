import { DatabaseClient } from '../database/supabase';
import { sha256Hex } from './jwt';

/**
 * Privacy-safe audit logger: hashes client IP and NEVER records PINs, tokens,
 * message contents, or media URLs.
 */
export async function recordAuditLog(
  db: DatabaseClient,
  event: {
    userId?: string | null;
    eventType: string;
    outcome: 'success' | 'failure' | 'blocked';
    ip?: string | null;
    meta?: Record<string, string | number | boolean>;
  }
): Promise<void> {
  const ipHash = event.ip ? (await sha256Hex(event.ip)).slice(0, 16) : null;
  try {
    await db.insertAuditLog({
      id: crypto.randomUUID(),
      userId: event.userId ?? null,
      eventType: event.eventType,
      outcome: event.outcome,
      ipHash,
      metaJson: event.meta ?? null,
      createdAtMs: Date.now(),
    });
  } catch (err) {
    console.error('audit_log_write_failed', {
      eventType: event.eventType,
      message: err instanceof Error ? err.message : 'unknown',
    });
  }
}
