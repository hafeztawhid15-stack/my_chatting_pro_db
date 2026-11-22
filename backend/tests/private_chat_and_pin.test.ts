import { describe, expect, it } from 'vitest';
import { Env } from '../src/config/env';
import { DatabaseClient, UserPrivatePinRow } from '../src/database/supabase';
import { verifyPrivateUnlockToken } from '../src/middleware/auth';
import { hashPin, verifyPin } from '../src/modules/auth/pin.crypto';
import { ConversationsService } from '../src/modules/conversations/conversations.service';
import { MessagesService } from '../src/modules/messages/messages.service';
import { PrivateChatService } from '../src/modules/private-chat/privateChat.service';

function makeTestEnv(): Env {
  return {
    PIN_PEPPER: 'test-pepper-secret-minimum-32-chars!!',
    JWT_ACCESS_SECRET: 'test-access-secret-minimum-32-chars!!',
    JWT_REFRESH_SECRET: 'test-refresh-secret-minimum-32-chars!!',
    MEDIA_SIGNING_SECRET: 'test-media-secret-minimum-32-chars!!',
    ACCESS_TOKEN_TTL_SECONDS: '900',
    REFRESH_TOKEN_TTL_SECONDS: '2592000',
    MEDIA_URL_TTL_SECONDS: '300',
    ENVIRONMENT: 'test',
  } as unknown as Env;
}

describe('Private Chat & Separate 5-Digit Private PIN Backend', () => {
  it('sets up, unlocks, rate-limits, changes, and safely resets separate Private PIN', async () => {
    const env = makeTestEnv();
    const loginPinHash = await hashPin('11111', env.PIN_PEPPER);

    let row: UserPrivatePinRow = {
      id: 'u1',
      pin_hash: loginPinHash,
      failed_login_count: 0,
      locked_until_ms: null,
      account_status: 'active',
      private_pin_hash: null,
      private_pin_failed_count: 0,
      private_pin_locked_until_ms: null,
      private_pin_updated_at_ms: null,
    };

    const fakeDb = {
      getUserPrivatePinState: async (uid: string) => (uid === 'u1' ? { ...row } : null),
      setPrivatePinHash: async (uid: string, hash: string, updatedAtMs: number) => {
        if (uid !== 'u1') return;
        row = {
          ...row,
          private_pin_hash: hash,
          private_pin_failed_count: 0,
          private_pin_locked_until_ms: null,
          private_pin_updated_at_ms: updatedAtMs,
        };
      },
      recordFailedPrivatePinAttempt: async (
        _uid: string,
        failedCount: number,
        lockedUntilMs: number | null
      ) => {
        row = {
          ...row,
          private_pin_failed_count: failedCount,
          private_pin_locked_until_ms: lockedUntilMs,
        };
      },
      clearPrivatePinLockout: async () => {
        row = { ...row, private_pin_failed_count: 0, private_pin_locked_until_ms: null };
      },
      recordFailedLogin: async (_uid: string, failedCount: number, lockedUntilMs: number | null) => {
        row = { ...row, failed_login_count: failedCount, locked_until_ms: lockedUntilMs };
      },
      insertAuditLog: async () => undefined,
    } as unknown as DatabaseClient;

    const service = new PrivateChatService(env, fakeDb);

    // 1. Initial status shows no Private PIN
    const initialStatus = await service.getPinStatus('u1');
    expect(initialStatus.hasPrivatePin).toBe(false);

    // 2. Setup rejects mismatched confirm PIN
    await expect(
      service.setupPrivatePin({
        userId: 'u1',
        sessionId: 's1',
        deviceId: 'd1',
        pin: '54321',
        confirmPin: '54322',
      })
    ).rejects.toMatchObject({ statusCode: 400, code: 'pin_mismatch' });

    // 3. Setup succeeds with matching 5-digit Private PIN (different from Login PIN 11111)
    const setupRes = await service.setupPrivatePin({
      userId: 'u1',
      sessionId: 's1',
      deviceId: 'd1',
      pin: '54321',
      confirmPin: '54321',
    });
    expect(setupRes.unlocked).toBe(true);
    expect(row.private_pin_hash).toBeTruthy();
    expect(row.private_pin_hash).not.toContain('54321');
    expect(await verifyPin('54321', row.private_pin_hash!, env.PIN_PEPPER)).toBe(true);
    // Login PIN must still work independently
    expect(await verifyPin('11111', row.pin_hash, env.PIN_PEPPER)).toBe(true);
    expect(await verifyPin('54321', row.pin_hash, env.PIN_PEPPER)).toBe(false);

    // 4. Token validates for user + session
    const verifiedToken = await verifyPrivateUnlockToken(
      setupRes.privateUnlockToken,
      env,
      fakeDb,
      { userId: 'u1', sessionId: 's1' }
    );
    expect(verifiedToken?.userId).toBe('u1');

    // Wrong session rejected
    const wrongSessionToken = await verifyPrivateUnlockToken(
      setupRes.privateUnlockToken,
      env,
      fakeDb,
      { userId: 'u1', sessionId: 's-other' }
    );
    expect(wrongSessionToken).toBeNull();

    // 5. Wrong PIN attempts trigger rate limiting / lockout after 5 failures
    for (let i = 0; i < 4; i++) {
      await expect(
        service.unlockPrivateChat({
          userId: 'u1',
          sessionId: 's1',
          deviceId: 'd1',
          pin: '99999',
        })
      ).rejects.toMatchObject({ statusCode: 401, code: 'wrong_private_pin' });
    }
    await expect(
      service.unlockPrivateChat({
        userId: 'u1',
        sessionId: 's1',
        deviceId: 'd1',
        pin: '99999',
      })
    ).rejects.toMatchObject({ statusCode: 429, code: 'private_pin_locked' });

    // Even valid PIN is blocked while locked out
    await expect(
      service.unlockPrivateChat({
        userId: 'u1',
        sessionId: 's1',
        deviceId: 'd1',
        pin: '54321',
      })
    ).rejects.toMatchObject({ statusCode: 429, code: 'private_pin_locked' });

    // 6. Reset requires verifying regular Account Login PIN (rejects wrong login PIN)
    await expect(
      service.resetPrivatePinWithAccountAuth({
        userId: 'u1',
        sessionId: 's1',
        deviceId: 'd1',
        loginPin: '00000',
        newPrivatePin: '67890',
        confirmNewPrivatePin: '67890',
      })
    ).rejects.toMatchObject({ statusCode: 401, code: 'wrong_pin' });

    // Reset succeeds ONLY with verified Login PIN (11111) + matching new Private PIN
    const resetRes = await service.resetPrivatePinWithAccountAuth({
      userId: 'u1',
      sessionId: 's1',
      deviceId: 'd1',
      loginPin: '11111',
      newPrivatePin: '67890',
      confirmNewPrivatePin: '67890',
    });
    expect(resetRes.unlocked).toBe(true);
    expect(row.private_pin_locked_until_ms).toBeNull();

    // Old unlock token from before reset is immediately invalidated if updated_at_ms bumped
    if (row.private_pin_updated_at_ms !== verifiedToken?.pver) {
      const oldTokenAfterReset = await verifyPrivateUnlockToken(
        setupRes.privateUnlockToken,
        env,
        fakeDb,
        { userId: 'u1', sessionId: 's1' }
      );
      expect(oldTokenAfterReset).toBeNull();
    }

    // 7. Change Private PIN verifies old Private PIN first
    await expect(
      service.changePrivatePin({
        userId: 'u1',
        sessionId: 's1',
        deviceId: 'd1',
        oldPin: '54321',
        newPin: '24680',
        confirmNewPin: '24680',
      })
    ).rejects.toMatchObject({ statusCode: 401, code: 'wrong_private_pin' });

    const changeRes = await service.changePrivatePin({
      userId: 'u1',
      sessionId: 's1',
      deviceId: 'd1',
      oldPin: '67890',
      newPin: '24680',
      confirmNewPin: '24680',
    });
    expect(changeRes.unlocked).toBe(true);
    expect(await verifyPin('24680', row.private_pin_hash!, env.PIN_PEPPER)).toBe(true);
  });

  it('isolates private conversations and messages from regular chat endpoints', async () => {
    const env = makeTestEnv();
    const fakeDb = {
      listConversationMemberIds: async () => ['u1', 'u2'],
      isConversationPrivate: async (convId: string) => (convId === 'priv-conv-1' ? true : false),
      listConversationMessages: async () => [],
      isBlockedBetween: async () => false,
    } as unknown as DatabaseClient;

    const convService = new ConversationsService(fakeDb);
    await expect(convService.assertMember('priv-conv-1', 'u1', false)).rejects.toMatchObject({
      statusCode: 403,
      code: 'private_chat_locked',
    });
    await expect(convService.assertMember('reg-conv-1', 'u1', true)).rejects.toMatchObject({
      statusCode: 403,
      code: 'forbidden_conversation',
    });

    const msgService = new MessagesService(env, fakeDb);
    await expect(msgService.listMessages('priv-conv-1', 'u1', undefined, false)).rejects.toMatchObject({
      statusCode: 403,
      code: 'private_chat_locked',
    });
    await expect(
      msgService.sendMessage(
        'u1',
        {
          conversationId: 'reg-conv-1',
          clientMessageId: 'cid-1',
          messageType: 'text',
          bodyText: 'Should fail on private route',
        },
        true
      )
    ).rejects.toMatchObject({
      statusCode: 403,
      code: 'forbidden_conversation',
    });
  });
});
