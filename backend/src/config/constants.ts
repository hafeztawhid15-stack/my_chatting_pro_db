export const ALLOWED_MEDIA_MIME_TYPES: Record<'profile' | 'image' | 'video' | 'audio' | 'file', string[]> = {
  profile: ['image/jpeg', 'image/png', 'image/webp'],
  image: ['image/jpeg', 'image/png', 'image/webp', 'image/gif'],
  video: ['video/mp4', 'video/quicktime', 'video/webm', 'video/3gpp'],
  audio: ['audio/mp4', 'audio/m4a', 'audio/aac', 'audio/mpeg', 'audio/ogg', 'audio/opus', 'audio/wav', 'audio/webm'],
  file: [
    'application/pdf',
    'application/zip',
    'application/x-zip-compressed',
    'application/vnd.android.package-archive',
    'application/msword',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'application/vnd.ms-excel',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    'application/vnd.ms-powerpoint',
    'application/vnd.openxmlformats-officedocument.presentationml.presentation',
    'text/plain',
    'text/csv',
    'application/json',
    'application/octet-stream',
  ],
};

export const EXECUTABLE_WARNING_EXTENSIONS = new Set(['apk', 'exe', 'bat', 'cmd', 'sh', 'msi', 'zip']);

export const MAX_PIN_ATTEMPTS_BEFORE_LOCK = 5;
export const LOCKOUT_DURATION_MS = 15 * 60 * 1000; // 15 minutes
export const DEFAULT_PAGE_LIMIT = 40;
export const MAX_PAGE_LIMIT = 80;
