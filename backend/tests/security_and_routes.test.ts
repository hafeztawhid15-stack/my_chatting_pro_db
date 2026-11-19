import { describe, expect, it } from 'vitest';
import { hashPin, verifyPin } from '../src/modules/auth/pin.crypto';
import { signJwt, verifyJwt } from '../src/security/jwt';
import { isValidFiveDigitPin, normalizePhoneE164, sanitizeFilename } from '../src/security/validation';
import { buildStoragePath, validateMediaConstraints } from '../src/storage/supabaseStorage';

describe('MY CHATTING PRO Security & Validation (Supabase + Cloudflare)', () => {
  it('normalizes valid E.164 phone numbers and rejects invalid ones', () => {
    expect(normalizePhoneE164('+880 1712-345678')).toBe('+8801712345678');
    expect(normalizePhoneE164('+1 (555) 234-5678')).toBe('+15552345678');
    expect(normalizePhoneE164('01712345678')).toBeNull();
    expect(normalizePhoneE164('+012345')).toBeNull();
  });

  it('validates 5-digit numeric PINs', () => {
    expect(isValidFiveDigitPin('12345')).toBe(true);
    expect(isValidFiveDigitPin('00000')).toBe(true);
    expect(isValidFiveDigitPin('1234')).toBe(false);
    expect(isValidFiveDigitPin('1234a')).toBe(false);
  });

  it('hashes and verifies PINs using per-user salt and pepper', async () => {
    const pepper = 'test-pepper-secret-32-bytes-minimum!!';
    const hash1 = await hashPin('48291', pepper);
    const hash2 = await hashPin('48291', pepper);

    expect(hash1).not.toBe(hash2);
    expect(hash1.startsWith('pbkdf2_sha512$100000$')).toBe(true);
    expect(await verifyPin('48291', hash1, pepper)).toBe(true);
    expect(await verifyPin('48292', hash1, pepper)).toBe(false);
    expect(await verifyPin('48291', hash1, 'wrong-pepper')).toBe(false);
  });

  it('signs and verifies access and refresh JWTs strictly by type', async () => {
    const secret = 'jwt-test-secret-with-sufficient-length';
    const access = await signJwt({ sub: 'u1', did: 'd1', sid: 's1', typ: 'access' }, secret, 60);

    const verifiedAccess = await verifyJwt(access, secret, 'access');
    const wrongType = await verifyJwt(access, secret, 'refresh');
    const wrongSecret = await verifyJwt(access, 'other-secret', 'access');

    expect(verifiedAccess?.sub).toBe('u1');
    expect(wrongType).toBeNull();
    expect(wrongSecret).toBeNull();
  });

  it('builds scoped private Supabase Storage paths, validates MIME/size, and sanitizes filenames', () => {
    expect(buildStoragePath('profile', 'u1', null, 'm1', '../../avatar.png')).toBe('users/u1/profile/m1.png');
    expect(buildStoragePath('image', 'u1', 'c1', 'm2', 'photo.jpg')).toBe('conversations/c1/images/m2.jpg');
    expect(buildStoragePath('file', 'u1', 'c1', 'm3', 'doc.pdf')).toBe('conversations/c1/files/m3.pdf');
    expect(sanitizeFilename('../weird name?.apk')).toBe('weird_name_.apk');

    const envLimits = {
      MAX_IMAGE_BYTES: '10485760',
      MAX_VIDEO_BYTES: '52428800',
      MAX_AUDIO_BYTES: '15728640',
      MAX_FILE_BYTES: '52428800',
    };
    expect(validateMediaConstraints(envLimits, 'image', 'image/jpeg', 1024).ok).toBe(true);
    expect(validateMediaConstraints(envLimits, 'image', 'application/x-msdownload', 1024).ok).toBe(false);
    expect(validateMediaConstraints(envLimits, 'image', 'image/png', 20 * 1024 * 1024).ok).toBe(false);
  });

  it('forbids creating a second account with the same phone number', async () => {
    const { AuthService } = await import('../src/modules/auth/auth.service');
    const { UniqueViolationError } = await import('../src/database/supabase');
    const phones = new Set<string>(['+8801712345678']);
    const fakeDb = {
      findAnyUserByPhone: async (phoneE164: string) => (phones.has(phoneE164) ? { id: 'existing-u1' } : null),
      registerUserAccount: async () => {
        throw new UniqueViolationError('23505 duplicate_account');
      },
      recordAuditLog: async () => undefined,
    } as unknown as import('../src/database/supabase').DatabaseClient;

    const fakeEnv = {
      PIN_PEPPER: 'pepper-secret-for-test-only-32-chars',
      JWT_ACCESS_SECRET: 'access-secret-for-test-only-32-chars',
      JWT_REFRESH_SECRET: 'refresh-secret-for-test-only-32-chars',
      ACCESS_TOKEN_TTL_SECONDS: '900',
      REFRESH_TOKEN_TTL_SECONDS: '2592000',
    } as unknown as import('../src/config/env').Env;

    const service = new AuthService(fakeEnv, fakeDb);

    const checkDup = await service.checkPhoneAvailable({ phone: '+880 1712-345678' });
    expect(checkDup.available).toBe(false);

    const checkFree = await service.checkPhoneAvailable({ phone: '+8801812345678' });
    expect(checkFree.available).toBe(true);

    await expect(
      service.register({
        phone: '+8801712345678',
        countryIso2: 'BD',
        callingCode: '+880',
        pin: '12345',
        confirmPin: '12345',
        displayName: 'Duplicate User',
      })
    ).rejects.toMatchObject({ statusCode: 409, code: 'duplicate_account' });

    // Even under a race where check passed, DB UniqueViolationError maps to 409 duplicate_account
    await expect(
      service.register({
        phone: '+8801812345678',
        countryIso2: 'BD',
        callingCode: '+880',
        pin: '12345',
        confirmPin: '12345',
        displayName: 'Race User',
      })
    ).rejects.toMatchObject({ statusCode: 409, code: 'duplicate_account' });
  });
});
