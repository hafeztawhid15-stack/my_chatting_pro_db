import { Hono } from 'hono';
import { Env } from '../../config/env';
import { AppVariables, authMiddleware } from '../../middleware/auth';
import { createSupabaseStorage } from '../../storage/supabaseStorage';
import { ProfilesService } from './profiles.service';

export const profilesRoutes = new Hono<{ Bindings: Env; Variables: AppVariables }>();

profilesRoutes.get('/me', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const service = new ProfilesService(c.env, c.get('db'), createSupabaseStorage(c.env));
  const profile = await service.getOwnProfile(auth.userId);
  return c.json(profile);
});

profilesRoutes.patch('/me', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const body = await c.req.json<{
    displayName?: string;
    about?: string;
    avatarMediaId?: string | null;
  }>();
  const service = new ProfilesService(c.env, c.get('db'), createSupabaseStorage(c.env));
  const updated = await service.updateOwnProfile(auth.userId, body);
  return c.json(updated);
});

profilesRoutes.post('/account/delete', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const body = await c.req.json<{ pin?: string; confirmText?: string }>();
  const service = new ProfilesService(c.env, c.get('db'), createSupabaseStorage(c.env));
  const result = await service.deleteAccountPermanently(
    auth.userId,
    body.pin ?? '',
    body.confirmText ?? '',
    c.req.header('CF-Connecting-IP')
  );
  return c.json(result, 200);
});
