export type RealtimeEventType =
  | 'connected'
  | 'ping'
  | 'pong'
  | 'sync.resume'
  | 'message.new'
  | 'message.delivered'
  | 'message.read'
  | 'message.deleted'
  | 'typing.start'
  | 'typing.stop'
  | 'presence.update'
  | 'call.invite'
  | 'call.ringing'
  | 'call.accept'
  | 'call.reject'
  | 'call.offer'
  | 'call.answer'
  | 'call.ice'
  | 'call.end';

export interface RealtimeEnvelope<T = Record<string, unknown>> {
  eventId: string;
  type: RealtimeEventType;
  timestampMs: number;
  payload: T;
}
