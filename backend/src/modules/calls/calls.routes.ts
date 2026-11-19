import { Hono } from 'hono';
import { Env } from '../../config/env';
import { AppVariables, authMiddleware } from '../../middleware/auth';
import { CallsService } from './calls.service';

export const callsRoutes = new Hono<{ Bindings: Env; Variables: AppVariables }>();

callsRoutes.get('/ice-servers', authMiddleware, async (c) => {
  const service = new CallsService(c.env, c.get('db'));
  const result = await service.getIceServers();
  return c.json(result);
});

callsRoutes.post('/', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const body = await c.req.json<{
    conversationId?: string;
    calleeId?: string;
    callType?: 'audio' | 'video';
  }>();
  const service = new CallsService(c.env, c.get('db'));
  const call = await service.initiateCall(auth.userId, {
    conversationId: body.conversationId ?? '',
    calleeId: body.calleeId ?? '',
    callType: body.callType === 'video' ? 'video' : 'audio',
  });
  return c.json({ call }, 201);
});

callsRoutes.post('/:callId/state', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const callId = c.req.param('callId') ?? '';
  const body = await c.req.json<{ action?: 'accept' | 'reject' | 'end' | 'missed' }>();
  const action = body.action ?? 'end';
  const service = new CallsService(c.env, c.get('db'));
  const result = await service.updateCallState(auth.userId, callId, action);
  return c.json(result);
});
