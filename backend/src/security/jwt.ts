const encoder = new TextEncoder();

export interface JwtPayload {
  sub: string;
  did: string;
  sid: string;
  typ: 'access' | 'refresh' | 'private_unlock';
  pver?: number;
  iat: number;
  exp: number;
  jti: string;
}

function base64UrlEncode(data: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < data.length; i++) {
    binary += String.fromCharCode(data[i]!);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function base64UrlDecode(input: string): Uint8Array {
  const padded = input.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((input.length + 3) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

async function importHmacKey(secret: string): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    'raw',
    encoder.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign', 'verify']
  );
}

export async function signJwt(
  payload: Omit<JwtPayload, 'iat' | 'exp' | 'jti'>,
  secret: string,
  ttlSeconds: number
): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const fullPayload: JwtPayload = {
    ...payload,
    iat: now,
    exp: now + ttlSeconds,
    jti: crypto.randomUUID(),
  };

  const headerSegment = base64UrlEncode(encoder.encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' })));
  const payloadSegment = base64UrlEncode(encoder.encode(JSON.stringify(fullPayload)));
  const signingInput = `${headerSegment}.${payloadSegment}`;

  const key = await importHmacKey(secret);
  const sigBuffer = await crypto.subtle.sign('HMAC', key, encoder.encode(signingInput));
  const sigSegment = base64UrlEncode(new Uint8Array(sigBuffer));
  return `${signingInput}.${sigSegment}`;
}

export async function verifyJwt(
  token: string,
  secret: string,
  expectedType: 'access' | 'refresh' | 'private_unlock'
): Promise<JwtPayload | null> {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const [headerSeg, payloadSeg, sigSeg] = parts;
  if (!headerSeg || !payloadSeg || !sigSeg) return null;

  try {
    const key = await importHmacKey(secret);
    const sigBytes = base64UrlDecode(sigSeg);
    const valid = await crypto.subtle.verify(
      'HMAC',
      key,
      sigBytes,
      encoder.encode(`${headerSeg}.${payloadSeg}`)
    );
    if (!valid) return null;

    const payloadJson = new TextDecoder().decode(base64UrlDecode(payloadSeg));
    const payload = JSON.parse(payloadJson) as JwtPayload;
    const now = Math.floor(Date.now() / 1000);
    if (payload.typ !== expectedType || !payload.sub || !payload.sid || payload.exp <= now) {
      return null;
    }
    return payload;
  } catch {
    return null;
  }
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', encoder.encode(value));
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

export function timingSafeEqualBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a[i]! ^ b[i]!;
  }
  return diff === 0;
}
