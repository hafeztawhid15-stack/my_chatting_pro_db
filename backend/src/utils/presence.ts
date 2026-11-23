export const PRESENCE_STALE_MS = 95_000;

export function computeIsFreshlyOnline(
  isOnline: unknown,
  lastSeenMs: unknown,
  nowMs: number = Date.now()
): boolean {
  if (!Boolean(isOnline)) return false;
  const lastSeen = typeof lastSeenMs === 'number' ? lastSeenMs : Number(lastSeenMs ?? 0);
  if (!Number.isFinite(lastSeen) || lastSeen <= 0) return false;
  return nowMs - lastSeen <= PRESENCE_STALE_MS;
}
