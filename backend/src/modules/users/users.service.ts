import { DatabaseClient } from '../../database/supabase';
import { ApiError } from '../../middleware/errorHandler';
import { normalizePhoneE164 } from '../../security/validation';

export interface PublicUserSearchResult {
  id: string;
  displayName: string;
  about: string;
  avatarMediaId: string | null;
  isOnline: boolean;
  lastSeenMs: number | null;
}

export class UsersService {
  constructor(private readonly db: DatabaseClient) {}

  async searchByPhoneAndOptionalName(
    requesterId: string,
    phoneRaw: string,
    nameFilter?: string
  ): Promise<PublicUserSearchResult | null> {
    const phoneE164 = normalizePhoneE164(phoneRaw);
    if (!phoneE164) {
      throw new ApiError(400, 'invalid_phone', 'Please provide a valid E.164 phone number.');
    }

    const row = await this.db.searchPublicUserByPhone(requesterId, phoneE164, nameFilter);
    if (!row) return null;

    return {
      id: row.id,
      displayName: row.display_name,
      about: row.about_text,
      avatarMediaId: row.avatar_media_id,
      isOnline: Boolean(row.is_online),
      lastSeenMs: row.last_seen_ms,
    };
  }
}
