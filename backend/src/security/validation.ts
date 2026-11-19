export function normalizePhoneE164(raw: string): string | null {
  const cleaned = raw.trim().replace(/[\s\-().]/g, '');
  if (!/^\+[1-9]\d{6,14}$/.test(cleaned)) {
    return null;
  }
  return cleaned;
}

export function isValidFiveDigitPin(pin: string): boolean {
  return /^\d{5}$/.test(pin);
}

export function sanitizeDisplayName(name: string): string | null {
  const trimmed = name.trim().replace(/\s+/g, ' ');
  if (trimmed.length < 1 || trimmed.length > 60) {
    return null;
  }
  return trimmed;
}

export function sanitizeAboutText(about: string | undefined): string {
  const trimmed = (about ?? '').trim();
  if (!trimmed) return 'Available on MY CHATTING PRO';
  return trimmed.slice(0, 140);
}

export function sanitizeFilename(name: string): string {
  const base = name.split(/[/\\]/).pop() ?? 'file';
  const cleaned = base.replace(/[^a-zA-Z0-9._-]/g, '_').slice(0, 120);
  return cleaned.length > 0 ? cleaned : 'file';
}

export function extractExtension(filename: string): string {
  const dotIdx = filename.lastIndexOf('.');
  if (dotIdx <= 0 || dotIdx === filename.length - 1) return 'bin';
  return filename.slice(dotIdx + 1).toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 10) || 'bin';
}
