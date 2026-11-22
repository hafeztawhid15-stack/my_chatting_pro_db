import { Env, getIntEnv } from '../../config/env';
import { LOCKOUT_DURATION_MS, MAX_PIN_ATTEMPTS_BEFORE_LOCK } from '../../config/constants';
import { DatabaseClient } from '../../database/supabase';
import { ApiError } from '../../middleware/errorHandler';
import { recordAuditLog } from '../../security/audit';
import { sha256Hex, signJwt, verifyJwt } from '../../security/jwt';
import {
  isValidFiveDigitPin,
  normalizePhoneE164,
  sanitizeDisplayName,
} from '../../security/validation';
import { dispatchSecurityAlert } from '../notifications/pushDispatcher';
import { hashPin, verifyPin } from './pin.crypto';

export interface AuthTokenBundle {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
  user: {
    id: string;
    phoneE164: string;
    displayName: string;
    about: string;
    avatarMediaId: string | null;
    phoneVerified: boolean;
  };
}

export class AuthService {
  constructor(
    private readonly env: Env,
    private readonly db: DatabaseClient
  ) {}

  async checkPhoneAvailable(input: {
    phone: string;
    clientIp?: string | null;
  }): Promise<{ available: boolean; phoneE164: string }> {
    const phoneE164 = normalizePhoneE164(input.phone);
    if (!phoneE164) {
      throw new ApiError(400, 'invalid_phone', 'Please provide a valid E.164 phone number.');
    }

    const existing = await this.db.findAnyUserByPhone(phoneE164);
    if (existing) {
      await recordAuditLog(this.db, {
        userId: existing.id,
        eventType: 'auth.check_phone_duplicate',
        outcome: 'blocked',
        ip: input.clientIp,
      });
      return { available: false, phoneE164 };
    }
    return { available: true, phoneE164 };
  }

  async register(input: {
    phone: string;
    countryIso2: string;
    callingCode: string;
    pin: string;
    confirmPin: string;
    displayName?: string;
    deviceName?: string;
    appVersion?: string;
    clientIp?: string | null;
  }): Promise<AuthTokenBundle> {
    const phoneE164 = normalizePhoneE164(input.phone);
    if (!phoneE164) {
      throw new ApiError(400, 'invalid_phone', 'Please provide a valid E.164 phone number.');
    }
    if (!isValidFiveDigitPin(input.pin)) {
      throw new ApiError(400, 'invalid_pin', 'PIN must be exactly 5 digits.');
    }
    if (input.pin !== input.confirmPin) {
      throw new ApiError(400, 'pin_mismatch', 'PIN confirmation does not match.');
    }

    const existing = await this.db.findAnyUserByPhone(phoneE164);
    if (existing) {
      await recordAuditLog(this.db, {
        userId: existing.id,
        eventType: 'auth.register_duplicate_blocked',
        outcome: 'blocked',
        ip: input.clientIp,
      });
      throw new ApiError(
        409,
        'duplicate_account',
        'An account with this phone number already exists. You cannot create a second account with the same number.'
      );
    }

    const displayName =
      sanitizeDisplayName(input.displayName ?? '') ??
      `User ${phoneE164.slice(-4)}`;
    const userId = crypto.randomUUID();
    const deviceId = crypto.randomUUID();
    const sessionId = crypto.randomUUID();
    const now = Date.now();
    const pinHash = await hashPin(input.pin, this.env.PIN_PEPPER);

    const accessTtl = getIntEnv(this.env.ACCESS_TOKEN_TTL_SECONDS, 900);
    const refreshTtl = getIntEnv(this.env.REFRESH_TOKEN_TTL_SECONDS, 2592000);
    const accessToken = await signJwt(
      { sub: userId, did: deviceId, sid: sessionId, typ: 'access' },
      this.env.JWT_ACCESS_SECRET,
      accessTtl
    );
    const refreshToken = await signJwt(
      { sub: userId, did: deviceId, sid: sessionId, typ: 'refresh' },
      this.env.JWT_REFRESH_SECRET,
      refreshTtl
    );
    const refreshHash = await sha256Hex(refreshToken);

    try {
      await this.db.registerUserAccount({
        userId,
        phoneE164,
        countryIso2: input.countryIso2.toUpperCase().slice(0, 2),
        callingCode: input.callingCode.slice(0, 8),
        pinHash,
        displayName,
        deviceId,
        deviceName: (input.deviceName ?? 'Android Device').slice(0, 80),
        appVersion: (input.appVersion ?? '1.0.0').slice(0, 24),
        sessionId,
        refreshHash,
        expiresAtMs: now + refreshTtl * 1000,
        nowMs: now,
      });
    } catch (err) {
      if (err instanceof Error && err.name === 'UniqueViolationError') {
        throw new ApiError(
          409,
          'duplicate_account',
          'An account with this phone number already exists. You cannot create a second account with the same number.'
        );
      }
      throw err;
    }

    await recordAuditLog(this.db, {
      userId,
      eventType: 'auth.register',
      outcome: 'success',
      ip: input.clientIp,
    });

    return {
      accessToken,
      refreshToken,
      expiresInSeconds: accessTtl,
      user: {
        id: userId,
        phoneE164,
        displayName,
        about: 'Available on MY CHATTING PRO',
        avatarMediaId: null,
        phoneVerified: false,
      },
    };
  }

  async login(input: {
    phone: string;
    pin: string;
    deviceName?: string;
    appVersion?: string;
    clientIp?: string | null;
  }): Promise<AuthTokenBundle> {
    const phoneE164 = normalizePhoneE164(input.phone);
    if (!phoneE164) {
      throw new ApiError(400, 'invalid_phone', 'Please provide a valid phone number.');
    }
    if (!isValidFiveDigitPin(input.pin)) {
      throw new ApiError(400, 'invalid_pin', 'PIN must be exactly 5 digits.');
    }

    const user = await this.db.findActiveUserByPhone(phoneE164);
    if (!user) {
      await recordAuditLog(this.db, {
        eventType: 'auth.login_unknown_account',
        outcome: 'failure',
        ip: input.clientIp,
      });
      throw new ApiError(404, 'account_not_found', 'No account found for this phone number.');
    }

    const now = Date.now();
    if (user.locked_until_ms && user.locked_until_ms > now) {
      const retryAfterSeconds = Math.ceil((user.locked_until_ms - now) / 1000);
      await recordAuditLog(this.db, {
        userId: user.id,
        eventType: 'auth.login_locked',
        outcome: 'blocked',
        ip: input.clientIp,
      });
      throw new ApiError(429, 'account_locked', 'Too many wrong PIN attempts. Account temporarily locked.', {
        retryAfterSeconds,
      });
    }

    const pinValid = await verifyPin(input.pin, user.pin_hash, this.env.PIN_PEPPER);
    if (!pinValid) {
      const nextCount = user.failed_login_count + 1;
      const shouldLock = nextCount >= MAX_PIN_ATTEMPTS_BEFORE_LOCK;
      const multiplier = shouldLock ? Math.pow(2, Math.min(nextCount - MAX_PIN_ATTEMPTS_BEFORE_LOCK, 4)) : 1;
      const lockDurationMs = shouldLock ? LOCKOUT_DURATION_MS * multiplier : null;
      const lockedUntil = lockDurationMs !== null ? now + lockDurationMs : null;

      await this.db.recordFailedLogin(user.id, nextCount, lockedUntil, now);
      await recordAuditLog(this.db, {
        userId: user.id,
        eventType: 'auth.login_wrong_pin',
        outcome: 'failure',
        ip: input.clientIp,
        meta: { failedCount: nextCount, locked: shouldLock },
      });
      if (nextCount >= 3) {
        await dispatchSecurityAlert(this.env, this.db, user.id, 'suspicious_login_attempt', {
          failedCount: nextCount,
        });
      }
      if (shouldLock && lockDurationMs !== null) {
        throw new ApiError(429, 'account_locked', 'Account temporarily locked due to repeated invalid PIN attempts.', {
          retryAfterSeconds: Math.ceil(lockDurationMs / 1000),
        });
      }
      throw new ApiError(401, 'wrong_pin', 'Incorrect 5-digit PIN.');
    }

    const profile = await this.db.getUserProfileBasic(user.id);

    const deviceId = crypto.randomUUID();
    const sessionId = crypto.randomUUID();
    const accessTtl = getIntEnv(this.env.ACCESS_TOKEN_TTL_SECONDS, 900);
    const refreshTtl = getIntEnv(this.env.REFRESH_TOKEN_TTL_SECONDS, 2592000);
    const accessToken = await signJwt(
      { sub: user.id, did: deviceId, sid: sessionId, typ: 'access' },
      this.env.JWT_ACCESS_SECRET,
      accessTtl
    );
    const refreshToken = await signJwt(
      { sub: user.id, did: deviceId, sid: sessionId, typ: 'refresh' },
      this.env.JWT_REFRESH_SECRET,
      refreshTtl
    );
    const refreshHash = await sha256Hex(refreshToken);

    await this.db.completeLoginSession({
      userId: user.id,
      deviceId,
      deviceName: (input.deviceName ?? 'Android Device').slice(0, 80),
      appVersion: (input.appVersion ?? '1.0.0').slice(0, 24),
      sessionId,
      refreshHash,
      expiresAtMs: now + refreshTtl * 1000,
      nowMs: now,
    });

    await recordAuditLog(this.db, {
      userId: user.id,
      eventType: 'auth.login',
      outcome: 'success',
      ip: input.clientIp,
    });

    await dispatchSecurityAlert(this.env, this.db, user.id, 'new_login', {
      deviceName: (input.deviceName ?? 'Android Device').slice(0, 60),
    });

    return {
      accessToken,
      refreshToken,
      expiresInSeconds: accessTtl,
      user: {
        id: user.id,
        phoneE164: user.phone_e164,
        displayName: profile?.display_name ?? 'MY CHATTING PRO User',
        about: profile?.about_text ?? 'Available on MY CHATTING PRO',
        avatarMediaId: profile?.avatar_media_id ?? null,
        phoneVerified: false,
      },
    };
  }

  async refresh(refreshToken: string, clientIp?: string | null): Promise<{
    accessToken: string;
    refreshToken: string;
    expiresInSeconds: number;
  }> {
    const payload = await verifyJwt(refreshToken, this.env.JWT_REFRESH_SECRET, 'refresh');
    if (!payload) {
      throw new ApiError(401, 'invalid_refresh_token', 'Invalid or expired refresh token.');
    }

    const tokenHash = await sha256Hex(refreshToken);
    const session = await this.db.getSessionById(payload.sid);

    const now = Date.now();
    if (!session || session.revoked_at_ms !== null || session.expires_at_ms < now) {
      throw new ApiError(401, 'session_revoked', 'Session has expired or been revoked.');
    }

    // Detect refresh token reuse (possible token theft): revoke entire session family
    if (session.refresh_token_hash !== tokenHash) {
      await this.db.revokeSessionById(session.id, null, now);
      await recordAuditLog(this.db, {
        userId: session.user_id,
        eventType: 'auth.refresh_reuse_detected',
        outcome: 'blocked',
        ip: clientIp,
      });
      throw new ApiError(401, 'session_compromised', 'Session revoked due to token replay detection.');
    }

    const accessTtl = getIntEnv(this.env.ACCESS_TOKEN_TTL_SECONDS, 900);
    const refreshTtl = getIntEnv(this.env.REFRESH_TOKEN_TTL_SECONDS, 2592000);
    const newAccessToken = await signJwt(
      { sub: session.user_id, did: session.device_id, sid: session.id, typ: 'access' },
      this.env.JWT_ACCESS_SECRET,
      accessTtl
    );
    const newRefreshToken = await signJwt(
      { sub: session.user_id, did: session.device_id, sid: session.id, typ: 'refresh' },
      this.env.JWT_REFRESH_SECRET,
      refreshTtl
    );
    const newRefreshHash = await sha256Hex(newRefreshToken);

    await this.db.rotateRefreshSession(session.id, newRefreshHash, now + refreshTtl * 1000, now);

    return {
      accessToken: newAccessToken,
      refreshToken: newRefreshToken,
      expiresInSeconds: accessTtl,
    };
  }

  async logout(sessionId: string, userId: string): Promise<void> {
    await this.db.revokeSessionById(sessionId, userId, Date.now());
  }

  async listActiveSessions(userId: string, currentSessionId: string): Promise<Array<{
    sessionId: string;
    deviceId: string;
    deviceName: string;
    platform: string;
    appVersion: string;
    lastUsedAtMs: number;
    createdAtMs: number;
    isCurrent: boolean;
  }>> {
    const rows = await this.db.listActiveUserSessions(userId, Date.now());
    return rows.map((row) => ({
      sessionId: row.session_id,
      deviceId: row.device_id,
      deviceName: row.device_name,
      platform: row.platform,
      appVersion: row.app_version,
      lastUsedAtMs: row.last_used_at_ms,
      createdAtMs: row.created_at_ms,
      isCurrent: row.session_id === currentSessionId,
    }));
  }

  async revokeSession(userId: string, targetSessionId: string): Promise<boolean> {
    const existing = await this.db.findSessionForRevoke(targetSessionId, userId);
    if (!existing) {
      return false;
    }
    await this.db.revokeSessionById(targetSessionId, userId, Date.now());
    await recordAuditLog(this.db, {
      userId,
      eventType: 'auth.session_revoked',
      outcome: 'success',
      meta: { targetSessionId },
    });
    await dispatchSecurityAlert(this.env, this.db, userId, 'session_revoked');
    return true;
  }

  async logoutAllDevices(
    userId: string,
    currentSessionId: string,
    currentDeviceId: string,
    keepCurrent: boolean,
    clientIp?: string | null
  ): Promise<{ revokedAll: boolean }> {
    const now = Date.now();
    await this.db.revokeAllUserSessions(userId, keepCurrent ? currentSessionId : null, now);
    if (!keepCurrent) {
      await this.db.deleteNotificationEndpoint(userId, currentDeviceId).catch(() => undefined);
    }
    await recordAuditLog(this.db, {
      userId,
      eventType: 'auth.logout_all_devices',
      outcome: 'success',
      ip: clientIp,
      meta: { keepCurrent },
    });
    await dispatchSecurityAlert(this.env, this.db, userId, 'logout_all_devices', { keepCurrent });
    return { revokedAll: true };
  }

  async changeLoginPin(
    userId: string,
    currentSessionId: string,
    input: { currentPin: string; newPin: string; confirmNewPin: string },
    clientIp?: string | null
  ): Promise<{ updatedAtMs: number; revokedOtherSessions: boolean }> {
    if (!isValidFiveDigitPin(input.currentPin) || !isValidFiveDigitPin(input.newPin)) {
      throw new ApiError(400, 'invalid_pin', 'PIN must be exactly 5 digits.');
    }
    if (input.newPin !== input.confirmNewPin) {
      throw new ApiError(400, 'pin_mismatch', 'New PIN confirmation does not match.');
    }
    if (input.currentPin === input.newPin) {
      throw new ApiError(400, 'invalid_pin', 'New PIN must be different from your current PIN.');
    }

    const row = await this.db.findActiveUserPinHash(userId);
    if (!row) {
      throw new ApiError(404, 'account_not_found', 'Account not found.');
    }
    const valid = await verifyPin(input.currentPin, row.pin_hash, this.env.PIN_PEPPER);
    if (!valid) {
      await recordAuditLog(this.db, {
        userId,
        eventType: 'auth.pin_change_wrong_pin',
        outcome: 'failure',
        ip: clientIp,
      });
      throw new ApiError(401, 'wrong_pin', 'Current 5-digit PIN is incorrect.');
    }

    const now = Date.now();
    const newHash = await hashPin(input.newPin, this.env.PIN_PEPPER);
    await this.db.updateUserLoginPin(userId, newHash, now);
    // Invalidate other sessions so any older compromised session loses access
    await this.db.revokeAllUserSessions(userId, currentSessionId, now);

    await recordAuditLog(this.db, {
      userId,
      eventType: 'auth.login_pin_changed',
      outcome: 'success',
      ip: clientIp,
    });
    await dispatchSecurityAlert(this.env, this.db, userId, 'login_pin_changed');

    return { updatedAtMs: now, revokedOtherSessions: true };
  }

  async generateRecoveryCode(
    userId: string,
    currentPin: string,
    clientIp?: string | null
  ): Promise<{ recoveryCode: string; generatedAtMs: number }> {
    if (!isValidFiveDigitPin(currentPin)) {
      throw new ApiError(400, 'invalid_pin', 'Current 5-digit PIN is required.');
    }
    const row = await this.db.findActiveUserPinHash(userId);
    if (!row) {
      throw new ApiError(404, 'account_not_found', 'Account not found.');
    }
    const valid = await verifyPin(currentPin, row.pin_hash, this.env.PIN_PEPPER);
    if (!valid) {
      throw new ApiError(401, 'wrong_pin', 'Incorrect 5-digit PIN.');
    }

    const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    const rand = new Uint8Array(12);
    crypto.getRandomValues(rand);
    let rawCode = '';
    for (let i = 0; i < rand.length; i++) {
      rawCode += alphabet[rand[i]! % alphabet.length];
    }
    const formattedCode = `${rawCode.slice(0, 4)}-${rawCode.slice(4, 8)}-${rawCode.slice(8, 12)}`;
    const hash = await hashPin(rawCode, this.env.PIN_PEPPER);
    const now = Date.now();
    await this.db.setRecoveryCodeHash(userId, hash, now);

    await recordAuditLog(this.db, {
      userId,
      eventType: 'auth.recovery_code_generated',
      outcome: 'success',
      ip: clientIp,
    });
    await dispatchSecurityAlert(this.env, this.db, userId, 'recovery_code_updated');

    return { recoveryCode: formattedCode, generatedAtMs: now };
  }

  async resetLoginPinWithRecoveryCode(input: {
    phone: string;
    recoveryCode: string;
    newPin: string;
    confirmNewPin: string;
    deviceName?: string;
    appVersion?: string;
    clientIp?: string | null;
  }): Promise<AuthTokenBundle> {
    const phoneE164 = normalizePhoneE164(input.phone);
    if (!phoneE164) {
      throw new ApiError(400, 'invalid_phone', 'Please provide a valid phone number.');
    }
    if (!isValidFiveDigitPin(input.newPin)) {
      throw new ApiError(400, 'invalid_pin', 'New PIN must be exactly 5 digits.');
    }
    if (input.newPin !== input.confirmNewPin) {
      throw new ApiError(400, 'pin_mismatch', 'New PIN confirmation does not match.');
    }
    const normalizedCode = input.recoveryCode.replace(/[^a-zA-Z0-9]/g, '').toUpperCase();
    if (normalizedCode.length < 8) {
      throw new ApiError(400, 'invalid_recovery_code', 'Please enter a valid 12-character Recovery Code.');
    }

    const user = await this.db.findUserRecoveryByPhone(phoneE164);
    if (!user) {
      throw new ApiError(404, 'account_not_found', 'No account found for this phone number.');
    }

    const now = Date.now();
    if (user.recovery_locked_until_ms && user.recovery_locked_until_ms > now) {
      const retryAfterSeconds = Math.ceil((user.recovery_locked_until_ms - now) / 1000);
      throw new ApiError(
        429,
        'account_locked',
        'Too many invalid recovery attempts. Recovery temporarily locked.',
        { retryAfterSeconds }
      );
    }

    if (!user.recovery_code_hash) {
      throw new ApiError(
        400,
        'recovery_code_not_set',
        'Recovery Code has not been generated for this account yet.'
      );
    }

    const codeValid = await verifyPin(normalizedCode, user.recovery_code_hash, this.env.PIN_PEPPER);
    if (!codeValid) {
      const nextCount = (user.recovery_failed_count ?? 0) + 1;
      const shouldLock = nextCount >= MAX_PIN_ATTEMPTS_BEFORE_LOCK;
      const lockedUntil = shouldLock ? now + LOCKOUT_DURATION_MS : null;
      await this.db.recordFailedRecoveryAttempt(user.id, nextCount, lockedUntil, now);
      await recordAuditLog(this.db, {
        userId: user.id,
        eventType: 'auth.recovery_code_failed',
        outcome: 'failure',
        ip: input.clientIp,
        meta: { failedCount: nextCount, locked: shouldLock },
      });
      await dispatchSecurityAlert(this.env, this.db, user.id, 'suspicious_login_attempt', {
        failedCount: nextCount,
      });
      if (shouldLock) {
        throw new ApiError(429, 'account_locked', 'Recovery temporarily locked due to invalid attempts.', {
          retryAfterSeconds: Math.ceil(LOCKOUT_DURATION_MS / 1000),
        });
      }
      throw new ApiError(401, 'invalid_recovery_code', 'Invalid Recovery Code.');
    }

    const newPinHash = await hashPin(input.newPin, this.env.PIN_PEPPER);
    await this.db.completeLoginPinRecovery(user.id, newPinHash, now);
    // Security: invalidate all prior active sessions before issuing a fresh session
    await this.db.revokeAllUserSessions(user.id, null, now);

    const profile = await this.db.getUserProfileBasic(user.id);
    const deviceId = crypto.randomUUID();
    const sessionId = crypto.randomUUID();
    const accessTtl = getIntEnv(this.env.ACCESS_TOKEN_TTL_SECONDS, 900);
    const refreshTtl = getIntEnv(this.env.REFRESH_TOKEN_TTL_SECONDS, 2592000);
    const accessToken = await signJwt(
      { sub: user.id, did: deviceId, sid: sessionId, typ: 'access' },
      this.env.JWT_ACCESS_SECRET,
      accessTtl
    );
    const refreshToken = await signJwt(
      { sub: user.id, did: deviceId, sid: sessionId, typ: 'refresh' },
      this.env.JWT_REFRESH_SECRET,
      refreshTtl
    );
    const refreshHash = await sha256Hex(refreshToken);

    await this.db.completeLoginSession({
      userId: user.id,
      deviceId,
      deviceName: (input.deviceName ?? 'Android Device').slice(0, 80),
      appVersion: (input.appVersion ?? '1.0.0').slice(0, 24),
      sessionId,
      refreshHash,
      expiresAtMs: now + refreshTtl * 1000,
      nowMs: now,
    });

    await recordAuditLog(this.db, {
      userId: user.id,
      eventType: 'auth.login_pin_reset_success',
      outcome: 'success',
      ip: input.clientIp,
    });
    await dispatchSecurityAlert(this.env, this.db, user.id, 'login_pin_reset');

    return {
      accessToken,
      refreshToken,
      expiresInSeconds: accessTtl,
      user: {
        id: user.id,
        phoneE164: user.phone_e164,
        displayName: profile?.display_name ?? 'MY CHATTING PRO User',
        about: profile?.about_text ?? 'Available on MY CHATTING PRO',
        avatarMediaId: profile?.avatar_media_id ?? null,
        phoneVerified: false,
      },
    };
  }
}
