import { timingSafeEqualBytes } from '../../security/jwt';

const encoder = new TextEncoder();
const PBKDF2_ITERATIONS = 100_000;
const HASH_BITS = 256;

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

function fromHex(hex: string): Uint8Array {
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return bytes;
}

async function applyPepper(pin: string, pepper: string): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey(
    'raw',
    encoder.encode(pepper),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const mac = await crypto.subtle.sign('HMAC', key, encoder.encode(pin));
  return new Uint8Array(mac);
}

/**
 * Hashes a 5-digit PIN using per-user 16-byte CSPRNG salt + server-side HMAC pepper
 * + PBKDF2-HMAC-SHA512 (100,000 iterations) in WebCrypto.
 * Never logs or stores plaintext PINs.
 */
export async function hashPin(pin: string, pepper: string): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const peppered = await applyPepper(pin, pepper);
  const keyMaterial = await crypto.subtle.importKey(
    'raw',
    peppered,
    'PBKDF2',
    false,
    ['deriveBits']
  );
  const derived = await crypto.subtle.deriveBits(
    {
      name: 'PBKDF2',
      salt,
      iterations: PBKDF2_ITERATIONS,
      hash: 'SHA-512',
    },
    keyMaterial,
    HASH_BITS
  );
  return `pbkdf2_sha512$${PBKDF2_ITERATIONS}$${toHex(salt)}$${toHex(new Uint8Array(derived))}`;
}

export async function verifyPin(pin: string, storedHash: string, pepper: string): Promise<boolean> {
  const parts = storedHash.split('$');
  if (parts.length !== 4 || parts[0] !== 'pbkdf2_sha512') return false;
  const iterations = Number.parseInt(parts[1] ?? '0', 10);
  const saltHex = parts[2];
  const expectedHex = parts[3];
  if (!iterations || !saltHex || !expectedHex) return false;

  const salt = fromHex(saltHex);
  const expected = fromHex(expectedHex);
  const peppered = await applyPepper(pin, pepper);
  const keyMaterial = await crypto.subtle.importKey(
    'raw',
    peppered,
    'PBKDF2',
    false,
    ['deriveBits']
  );
  const derived = await crypto.subtle.deriveBits(
    {
      name: 'PBKDF2',
      salt,
      iterations,
      hash: 'SHA-512',
    },
    keyMaterial,
    expected.length * 8
  );

  return timingSafeEqualBytes(new Uint8Array(derived), expected);
}
