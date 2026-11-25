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

callsRoutes.get('/history', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const filter = c.req.query('filter');
  const limitRaw = c.req.query('limit');
  const limit = limitRaw ? Number.parseInt(limitRaw, 10) : undefined;
  const service = new CallsService(c.env, c.get('db'));
  const calls = await service.getCallHistory(
    auth.userId,
    filter,
    Number.isFinite(limit) ? limit : undefined
  );
  return c.json({ calls });
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

callsRoutes.get('/:callId', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const callId = c.req.param('callId') ?? '';
  const service = new CallsService(c.env, c.get('db'));
  const result = await service.getCallStatus(auth.userId, callId);
  return c.json(result);
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

callsRoutes.post('/:callId/signal', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const callId = c.req.param('callId') ?? '';
  const body = await c.req.json<{
    type?: string;
    conversationId?: string;
    targetUserId?: string;
    payload?: Record<string, unknown>;
  }>();
  const service = new CallsService(c.env, c.get('db'));
  const result = await service.relayCallSignal(auth.userId, callId, body);
  return c.json(result);
});

callsRoutes.get('/:callId/signals', authMiddleware, async (c) => {
  const auth = c.get('auth');
  const callId = c.req.param('callId') ?? '';
  const afterRaw = c.req.query('afterMs');
  const afterMs = afterRaw ? Number.parseInt(afterRaw, 10) : 0;
  const service = new CallsService(c.env, c.get('db'));
  const result = await service.pullCallSignals(
    auth.userId,
    callId,
    Number.isFinite(afterMs) && afterMs > 0 ? afterMs : 0
  );
  return c.json(result);
});
