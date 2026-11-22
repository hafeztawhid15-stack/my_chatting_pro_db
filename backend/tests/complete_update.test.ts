import { describe, expect, it } from 'vitest';
import { Env } from '../src/config/env';
import { DatabaseClient, UserAccountRow } from '../src/database/supabase';
import { AuthService } from '../src/modules/auth/auth.service';
import { hashPin, verifyPin } from '../src/modules/auth/pin.crypto';
import { CallsService } from '../src/modules/calls/calls.service';
import { ConversationsService } from '../src/modules/conversations/conversations.service';
import { ProfilesService } from '../src/modules/profiles/profiles.service';
import { validateMediaConstraints } from '../src/storage/supabaseStorage';

function makeTestEnv(): Env {
  return {
    PIN_PEPPER: 'test-pepper-secret-minimum-32-chars!!',
    JWT_ACCESS_SECRET: 'test-access-secret-minimum-32-chars!!',
    JWT_REFRESH_SECRET: 'test-refresh-secret-minimum-32-chars!!',
    MEDIA_SIGNING_SECRET: 'test-media-secret-minimum-32-chars!!',
    ACCESS_TOKEN_TTL_SECONDS: '900',
    REFRESH_TOKEN_TTL_SECONDS: '2592000',
    MEDIA_URL_TTL_SECONDS: '300',
    MAX_IMAGE_BYTES: '10485760',
    MAX_VIDEO_BYTES: '52428800',
    MAX_AUDIO_BYTES: '15728640',
    MAX_FILE_BYTES: '52428800',
    ENVIRONMENT: 'test',
    USER_COORDINATOR: {
      idFromName: (id: string) => id as unknown as DurableObjectId,
      get: () => ({
        fetch: async () => new Response(JSON.stringify({ ok: true }), { status: 200 }),
      }),
    } as unknown as DurableObjectNamespace,
  } as unknown as Env;
}

describe('Complete Update: Media MIME, PIN Security, Chat Menu, Account Delete & Calls', () => {
  it('accepts Android voice/image MIME aliases and classifies media constraint codes', () => {
    const env = makeTestEnv();
    expect(validateMediaConstraints(env, 'audio', 'audio/x-m4a', 1024).ok).toBe(true);
    expect(validateMediaConstraints(env, 'audio', 'audio/mp4a-latm; codecs=mp4a.40.2', 2048).ok).toBe(true);
    expect(validateMediaConstraints(env, 'image', 'image/jpg', 4096).ok).toBe(true);

    expect(validateMediaConstraints(env, 'audio', 'application/octet-stream', 1024)).toMatchObject({
      ok: false,
      code: 'unsupported_media_type',
    });
    expect(validateMediaConstraints(env, 'image', 'image/jpeg', 20 * 1024 * 1024)).toMatchObject({
      ok: false,
      code: 'media_too_large',
    });
  });

  it('changes Login PIN, generates Recovery Code, resets PIN cleanly, and revokes old sessions', async () => {
    const env = makeTestEnv();
    let userRow: UserAccountRow = {
      id: 'u1',
      phone_e164: '+8801712345678',
      country_iso2: 'BD',
      calling_code: '+880',
      pin_hash: await hashPin('12345', env.PIN_PEPPER),
      role: 'user',
      failed_login_count: 0,
      locked_until_ms: null,
      account_status: 'active',
      recovery_code_hash: null,
      recovery_code_updated_at_ms: null,
      recovery_failed_count: 0,
      recovery_locked_until_ms: null,
    };
    const revokedAllCalls: Array<{ except: string | null }> = [];

    const fakeDb = {
      findActiveUserPinHash: async (uid: string) => (uid === 'u1' ? { pin_hash: userRow.pin_hash } : null),
      findUserRecoveryByPhone: async (phone: string) =>
        phone === userRow.phone_e164 ? { ...userRow } : null,
      updateUserLoginPin: async (_uid: string, hash: string) => {
        userRow = { ...userRow, pin_hash: hash };
      },
      setRecoveryCodeHash: async (_uid: string, hash: string, nowMs: number) => {
        userRow = {
          ...userRow,
          recovery_code_hash: hash,
          recovery_code_updated_at_ms: nowMs,
          recovery_failed_count: 0,
          recovery_locked_until_ms: null,
        };
      },
      recordFailedRecoveryAttempt: async (_uid: string, failedCount: number, lockedUntil: number | null) => {
        userRow = { ...userRow, recovery_failed_count: failedCount, recovery_locked_until_ms: lockedUntil };
      },
      completeLoginPinRecovery: async (_uid: string, newPinHash: string) => {
        userRow = {
          ...userRow,
          pin_hash: newPinHash,
          recovery_failed_count: 0,
          recovery_locked_until_ms: null,
        };
      },
      revokeAllUserSessions: async (_uid: string, except: string | null) => {
        revokedAllCalls.push({ except });
      },
      getUserProfileBasic: async () => ({
        display_name: 'Tawhid',
        about_text: 'Active',
        avatar_media_id: null,
      }),
      completeLoginSession: async () => undefined,
      listNotificationEndpointsForUser: async () => [],
      deleteNotificationEndpoint: async () => undefined,
      insertAuditLog: async () => undefined,
    } as unknown as DatabaseClient;

    const authService = new AuthService(env, fakeDb);

    // 1. Recovery without setting a code fails
    await expect(
      authService.resetLoginPinWithRecoveryCode({
        phone: '+8801712345678',
        recoveryCode: 'ABCD-EFGH-IJKL',
        newPin: '54321',
        confirmNewPin: '54321',
      })
    ).rejects.toMatchObject({ statusCode: 400, code: 'recovery_code_not_set' });

    // 2. Generate recovery code requires current PIN
    await expect(authService.generateRecoveryCode('u1', '00000')).rejects.toMatchObject({
      statusCode: 401,
      code: 'wrong_pin',
    });
    const generated = await authService.generateRecoveryCode('u1', '12345');
    expect(generated.recoveryCode).toMatch(/^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$/);

    // 3. Change Login PIN invalidates older sessions
    await expect(
      authService.changeLoginPin('u1', 's1', {
        currentPin: '12345',
        newPin: '23456',
        confirmNewPin: '23456',
      })
    ).resolves.toMatchObject({ revokedOtherSessions: true });
    expect(await verifyPin('23456', userRow.pin_hash, env.PIN_PEPPER)).toBe(true);
    expect(revokedAllCalls[0]?.except).toBe('s1');

    // 4. Wrong recovery code fails with 401
    await expect(
      authService.resetLoginPinWithRecoveryCode({
        phone: '+8801712345678',
        recoveryCode: 'ZZZZ-ZZZZ-ZZZZ',
        newPin: '67890',
        confirmNewPin: '67890',
      })
    ).rejects.toMatchObject({ statusCode: 401, code: 'invalid_recovery_code' });

    // 5. Valid recovery code resets PIN AND revokes ALL prior active sessions (except = null)
    const resetResult = await authService.resetLoginPinWithRecoveryCode({
      phone: '+8801712345678',
      recoveryCode: generated.recoveryCode,
      newPin: '67890',
      confirmNewPin: '67890',
    });
    expect(resetResult.accessToken.length).toBeGreaterThan(20);
    expect(await verifyPin('67890', userRow.pin_hash, env.PIN_PEPPER)).toBe(true);
    expect(revokedAllCalls[1]?.except).toBeNull();
  });

  it('requires typed DELETE confirmation alongside PIN before permanent account deletion', async () => {
    const env = makeTestEnv();
    let deletedTxCalled = false;
    const fakeDb = {
      findActiveUserPinHash: async () => ({ pin_hash: await hashPin('12345', env.PIN_PEPPER) }),
      listUserMediaPaths: async () => [],
      deleteUserAccountTx: async () => {
        deletedTxCalled = true;
      },
      listNotificationEndpointsForUser: async () => [],
      insertAuditLog: async () => undefined,
    } as unknown as DatabaseClient;

    const profiles = new ProfilesService(env, fakeDb);
    await expect(
      profiles.deleteAccountPermanently('u1', '12345', 'delete')
    ).rejects.toMatchObject({ statusCode: 400, code: 'invalid_delete_confirmation' });
    expect(deletedTxCalled).toBe(false);

    const res = await profiles.deleteAccountPermanently('u1', '12345', 'DELETE');
    expect(res.deleted).toBe(true);
    expect(deletedTxCalled).toBe(true);
  });

  it('enforces per-user chat menu preferences isolation & call history filtering', async () => {
    const env = makeTestEnv();
    const savedPrefs: Array<Record<string, unknown>> = [];
    const fakeDb = {
      listConversationMemberIds: async () => ['u1', 'u2'],
      isConversationPrivate: async (id: string) => id.startsWith('priv-'),
      upsertUserConversationSettings: async (input: Record<string, unknown>) => {
        savedPrefs.push(input);
      },
      listUserCalls: async (_uid: string, filter: string) => [
        {
          call_id: 'c1',
          conversation_id: 'conv1',
          caller_id: 'u2',
          peer_user_id: 'u2',
          peer_display_name: 'Friend',
          peer_avatar_media_id: null,
          call_type: 'video' as const,
          status: filter === 'missed' ? ('missed' as const) : ('ended' as const),
          direction: 'incoming' as const,
          started_at_ms: 1000,
          answered_at_ms: null,
          ended_at_ms: 2000,
          duration_seconds: 12,
        },
      ],
    } as unknown as DatabaseClient;

    const convService = new ConversationsService(fakeDb);
    await expect(
      convService.updateConversationPreferences('u1', 'priv-1', { isPinned: true }, false)
    ).rejects.toMatchObject({ statusCode: 403, code: 'private_chat_locked' });

    await convService.updateConversationPreferences(
      'u1',
      'reg-1',
      { isPinned: true, customSoundUri: 'content://settings/system/notification_sound', customSoundLabel: 'Default' },
      false
    );
    expect(savedPrefs[0]).toMatchObject({ isPinned: true, conversationId: 'reg-1' });

    const callsService = new CallsService(env, fakeDb);
    const missed = await callsService.getCallHistory('u1', 'missed', 20);
    expect(missed[0]).toMatchObject({ callId: 'c1', status: 'missed', durationSeconds: 12 });
  });
});
