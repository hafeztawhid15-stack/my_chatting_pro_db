import { Hono } from 'hono';
import { Env } from '../../config/env';
import { AppVariables, authMiddleware } from '../../middleware/auth';
import { rateLimitMiddleware } from '../../middleware/rateLimit';
import { UsersService } from './users.service';

export const usersRoutes = new Hono<{ Bindings: Env; Variables: AppVariables }>();

usersRoutes.get(
  '/search',
  authMiddleware,
  rateLimitMiddleware({ prefix: 'user_search', maxRequests: 25, windowMs: 60_000, blockMs: 120_000, keyScope: 'ip_and_user' }),
  async (c) => {
    const auth = c.get('auth');
    const phone = c.req.query('phone') ?? '';
    const name = c.req.query('name');

    const service = new UsersService(c.get('db'));
    const user = await service.searchByPhoneAndOptionalName(auth.userId, phone, name);
    if (!user) {
      return c.json({ found: false, user: null, message: 'No My Chatting Pro account found.' }, 200);
    }
    return c.json({ found: true, user }, 200);
  }
);
