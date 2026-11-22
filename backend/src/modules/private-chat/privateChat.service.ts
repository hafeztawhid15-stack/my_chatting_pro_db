import { LOCKOUT_DURATION_MS, MAX_PIN_ATTEMPTS_BEFORE_LOCK } from '../../config/constants';
import { Env } from '../../config/env';
import { DatabaseClient, UserPrivatePinRow } from '../../database/supabase';
import { derivePrivateUnlockSecret } from '../../middleware/auth';
import { ApiError } from '../../middleware/errorHandler';
import { recordAuditLog } from '../../security/audit';
import { signJwt } from '../../security/jwt';
import { isValidFiveDigitPin } from '../../security/validation';
import { hashPin, verifyPin } from '../auth/pin.crypto';

const PRIVATE_UNLOCK_TTL_SECONDS = 600;

export interface PrivatePinStatusResult {
  hasPrivatePin: boolean;
  lockedUntilMs: number | null;
  retryAfterSeconds: number;
}

export interface PrivatePinUnlockResult {
  unlocked: boolean;
  privateUnlockToken: string;
  expiresInSeconds: number;
}

export class PrivateChatService {
  constructor(
    private readonly env: Env,
    private readonly db: DatabaseClient
  ) {}

  async getPinStatus(userId: string): Promise<PrivatePinStatusResult> {
    const state = await this.requireActivePinRow(userId);
    const now = Date.now();
    const lockedUntil = state.private_pin_locked_until_ms;
    const activeLock = lockedUntil && lockedUntil > now ? lockedUntil : null;
    const retryAfterSeconds = activeLock ? Math.max(1, Math.ceil((activeLock - now) / 1000)) : 0;

    return {
      hasPrivatePin: Boolean(state.private_pin_hash),
      lockedUntilMs: activeLock,
      retryAfterSeconds,
    };
  }

  async setupPrivatePin(input: {
    userId: string;
    sessionId: string;
    deviceId: string;
    pin: string;
    confirmPin: string;
    clientIp?: string | null;
  }): Promise<PrivatePinUnlockResult> {
    const state = await this.requireActivePinRow(input.userId);
    if (state.private_pin_hash) {
      throw new ApiError(409, 'private_pin_already_set', 'Private PIN is already configured.');
    }

    if (!isValidFiveDigitPin(input.pin)) {
      throw new ApiError(400, 'invalid_pin', 'Private PIN must be exactly 5 digits.');
    }
    if (input.pin !== input.confirmPin) {
      throw new ApiError(400, 'pin_mismatch', 'Private PIN confirmation does not match.');
    }

    const now = Date.now();
    const pinHash = await hashPin(input.pin, this.env.PIN_PEPPER);
    await this.db.setPrivatePinHash(input.userId, pinHash, now);

    await recordAuditLog(this.db, {
      userId: input.userId,
      eventType: 'private_pin.setup',
      outcome: 'success',
      ip: input.clientIp,
    });

    const token = await this.issueUnlockToken(input.userId, input.deviceId, input.sessionId, now);
    return {
      unlocked: true,
      privateUnlockToken: token,
      expiresInSeconds: PRIVATE_UNLOCK_TTL_SECONDS,
    };
  }

  async unlockPrivateChat(input: {
    userId: string;
    sessionId: string;
    deviceId: string;
    pin: string;
    clientIp?: string | null;
  }): Promise<PrivatePinUnlockResult> {
    const state = await this.requireActivePinRow(input.userId);
    if (!state.private_pin_hash) {
      throw new ApiError(404, 'private_pin_not_set', 'Private PIN has not been created yet.');
    }

    if (!isValidFiveDigitPin(input.pin)) {
      throw new ApiError(400, 'invalid_pin', 'Private PIN must be exactly 5 digits.');
    }

    const now = Date.now();
    if (state.private_pin_locked_until_ms && state.private_pin_locked_until_ms > now) {
      const retryAfterSeconds = Math.max(1, Math.ceil((state.private_pin_locked_until_ms - now) / 1000));
      await recordAuditLog(this.db, {
        userId: input.userId,
        eventType: 'private_pin.unlock_locked',
        outcome: 'blocked',
        ip: input.clientIp,
      });
      throw new ApiError(
        429,
        'private_pin_locked',
        'Too many incorrect Private PIN attempts. Temporarily locked.',
        { retryAfterSeconds }
      );
    }

    const pinValid = await verifyPin(input.pin, state.private_pin_hash, this.env.PIN_PEPPER);
    if (!pinValid) {
      const nextCount = (state.private_pin_failed_count ?? 0) + 1;
      const shouldLock = nextCount >= MAX_PIN_ATTEMPTS_BEFORE_LOCK;
      const multiplier = shouldLock
        ? Math.pow(2, Math.min(nextCount - MAX_PIN_ATTEMPTS_BEFORE_LOCK, 4))
        : 1;
      const lockDurationMs = shouldLock ? LOCKOUT_DURATION_MS * multiplier : null;
      const lockedUntil = lockDurationMs !== null ? now + lockDurationMs : null;

      await this.db.recordFailedPrivatePinAttempt(input.userId, nextCount, lockedUntil, now);
      await recordAuditLog(this.db, {
        userId: input.userId,
        eventType: 'private_pin.unlock_wrong_pin',
        outcome: 'failure',
        ip: input.clientIp,
        meta: { failedCount: nextCount, locked: shouldLock },
      });

      if (shouldLock && lockDurationMs !== null) {
        throw new ApiError(
          429,
          'private_pin_locked',
          'Too many incorrect Private PIN attempts. Temporarily locked.',
          { retryAfterSeconds: Math.ceil(lockDurationMs / 1000) }
        );
      }

      throw new ApiError(401, 'wrong_private_pin', 'Incorrect 5-digit Private PIN.');
    }

    if (state.private_pin_failed_count > 0 || state.private_pin_locked_until_ms !== null) {
      await this.db.clearPrivatePinLockout(input.userId, now);
    }

    await recordAuditLog(this.db, {
      userId: input.userId,
      eventType: 'private_pin.unlock',
      outcome: 'success',
      ip: input.clientIp,
    });

    const pver = state.private_pin_updated_at_ms ?? 0;
    const token = await this.issueUnlockToken(input.userId, input.deviceId, input.sessionId, pver);
    return {
      unlocked: true,
      privateUnlockToken: token,
      expiresInSeconds: PRIVATE_UNLOCK_TTL_SECONDS,
    };
  }

  async changePrivatePin(input: {
    userId: string;
    sessionId: string;
    deviceId: string;
    oldPin: string;
    newPin: string;
    confirmNewPin: string;
    clientIp?: string | null;
  }): Promise<PrivatePinUnlockResult> {
    const state = await this.requireActivePinRow(input.userId);
    if (!state.private_pin_hash) {
      throw new ApiError(404, 'private_pin_not_set', 'Private PIN has not been created yet.');
    }

    if (!isValidFiveDigitPin(input.oldPin) || !isValidFiveDigitPin(input.newPin)) {
      throw new ApiError(400, 'invalid_pin', 'Private PIN must be exactly 5 digits.');
    }
    if (input.newPin !== input.confirmNewPin) {
      throw new ApiError(400, 'pin_mismatch', 'New Private PIN confirmation does not match.');
    }

    const now = Date.now();
    if (state.private_pin_locked_until_ms && state.private_pin_locked_until_ms > now) {
      const retryAfterSeconds = Math.max(1, Math.ceil((state.private_pin_locked_until_ms - now) / 1000));
      throw new ApiError(
        429,
        'private_pin_locked',
        'Too many incorrect Private PIN attempts. Temporarily locked.',
        { retryAfterSeconds }
      );
    }

    const oldValid = await verifyPin(input.oldPin, state.private_pin_hash, this.env.PIN_PEPPER);
    if (!oldValid) {
      const nextCount = (state.private_pin_failed_count ?? 0) + 1;
      const shouldLock = nextCount >= MAX_PIN_ATTEMPTS_BEFORE_LOCK;
      const multiplier = shouldLock
        ? Math.pow(2, Math.min(nextCount - MAX_PIN_ATTEMPTS_BEFORE_LOCK, 4))
        : 1;
      const lockDurationMs = shouldLock ? LOCKOUT_DURATION_MS * multiplier : null;
      const lockedUntil = lockDurationMs !== null ? now + lockDurationMs : null;

      await this.db.recordFailedPrivatePinAttempt(input.userId, nextCount, lockedUntil, now);
      await recordAuditLog(this.db, {
        userId: input.userId,
        eventType: 'private_pin.change_wrong_old_pin',
        outcome: 'failure',
        ip: input.clientIp,
      });

      if (shouldLock && lockDurationMs !== null) {
        throw new ApiError(
          429,
          'private_pin_locked',
          'Too many incorrect Private PIN attempts. Temporarily locked.',
          { retryAfterSeconds: Math.ceil(lockDurationMs / 1000) }
        );
      }

      throw new ApiError(401, 'wrong_private_pin', 'Current Private PIN is incorrect.');
    }

    const newHash = await hashPin(input.newPin, this.env.PIN_PEPPER);
    await this.db.setPrivatePinHash(input.userId, newHash, now);

    await recordAuditLog(this.db, {
      userId: input.userId,
      eventType: 'private_pin.changed',
      outcome: 'success',
      ip: input.clientIp,
    });

    const token = await this.issueUnlockToken(input.userId, input.deviceId, input.sessionId, now);
    return {
      unlocked: true,
      privateUnlockToken: token,
      expiresInSeconds: PRIVATE_UNLOCK_TTL_SECONDS,
    };
  }

  /**
   * Resets Private PIN ONLY after verifying:
   * 1) Active authenticated session (`authMiddleware`)
   * 2) User's existing 5-digit Account Login PIN (`users.pin_hash`)
   * Never permits resetting solely from a phone number.
   */
  async resetPrivatePinWithAccountAuth(input: {
    userId: string;
    sessionId: string;
    deviceId: string;
    loginPin: string;
    newPrivatePin: string;
    confirmNewPrivatePin: string;
    clientIp?: string | null;
  }): Promise<PrivatePinUnlockResult> {
    const state = await this.requireActivePinRow(input.userId);
    if (!isValidFiveDigitPin(input.loginPin) || !isValidFiveDigitPin(input.newPrivatePin)) {
      throw new ApiError(400, 'invalid_pin', 'PIN must be exactly 5 digits.');
    }
    if (input.newPrivatePin !== input.confirmNewPrivatePin) {
      throw new ApiError(400, 'pin_mismatch', 'New Private PIN confirmation does not match.');
    }

    const now = Date.now();
    if (state.locked_until_ms && state.locked_until_ms > now) {
      const retryAfterSeconds = Math.max(1, Math.ceil((state.locked_until_ms - now) / 1000));
      throw new ApiError(429, 'account_locked', 'Too many wrong Login PIN attempts.', {
        retryAfterSeconds,
      });
    }

    const loginPinValid = await verifyPin(input.loginPin, state.pin_hash, this.env.PIN_PEPPER);
    if (!loginPinValid) {
      const nextCount = (state.failed_login_count ?? 0) + 1;
      const shouldLock = nextCount >= MAX_PIN_ATTEMPTS_BEFORE_LOCK;
      const multiplier = shouldLock
        ? Math.pow(2, Math.min(nextCount - MAX_PIN_ATTEMPTS_BEFORE_LOCK, 4))
        : 1;
      const lockDurationMs = shouldLock ? LOCKOUT_DURATION_MS * multiplier : null;
      const lockedUntil = lockDurationMs !== null ? now + lockDurationMs : null;

      await this.db.recordFailedLogin(input.userId, nextCount, lockedUntil, now);
      await recordAuditLog(this.db, {
        userId: input.userId,
        eventType: 'private_pin.reset_auth_failed',
        outcome: 'failure',
        ip: input.clientIp,
      });

      if (shouldLock && lockDurationMs !== null) {
        throw new ApiError(429, 'account_locked', 'Account temporarily locked due to wrong Login PIN attempts.', {
          retryAfterSeconds: Math.ceil(lockDurationMs / 1000),
        });
      }

      throw new ApiError(401, 'wrong_pin', 'Incorrect account Login PIN. Private PIN reset denied.');
    }

    const newHash = await hashPin(input.newPrivatePin, this.env.PIN_PEPPER);
    await this.db.setPrivatePinHash(input.userId, newHash, now);

    await recordAuditLog(this.db, {
      userId: input.userId,
      eventType: 'private_pin.reset',
      outcome: 'success',
      ip: input.clientIp,
    });

    const token = await this.issueUnlockToken(input.userId, input.deviceId, input.sessionId, now);
    return {
      unlocked: true,
      privateUnlockToken: token,
      expiresInSeconds: PRIVATE_UNLOCK_TTL_SECONDS,
    };
  }

  private async requireActivePinRow(userId: string): Promise<UserPrivatePinRow> {
    const row = await this.db.getUserPrivatePinState(userId);
    if (!row || row.account_status !== 'active') {
      throw new ApiError(404, 'account_not_found', 'Active account not found.');
    }
    return row;
  }

  private async issueUnlockToken(
    userId: string,
    deviceId: string,
    sessionId: string,
    pver: number
  ): Promise<string> {
    const secret = await derivePrivateUnlockSecret(this.env);
    return signJwt(
      {
        sub: userId,
        did: deviceId,
        sid: sessionId,
        typ: 'private_unlock',
        pver,
      },
      secret,
      PRIVATE_UNLOCK_TTL_SECONDS
    );
  }
}
