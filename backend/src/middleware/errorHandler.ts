import { Context } from 'hono';

export class ApiError extends Error {
  constructor(
    public readonly statusCode: number,
    public readonly code: string,
    message: string,
    public readonly extra?: Record<string, unknown>
  ) {
    super(message);
  }
}

export function handleWorkerError(err: unknown, c: Context) {
  if (err instanceof ApiError) {
    return c.json(
      {
        error: err.code,
        message: err.message,
        ...(err.extra ?? {}),
      },
      err.statusCode as 400
    );
  }

  console.error('unhandled_worker_error', {
    name: err instanceof Error ? err.name : 'UnknownError',
    message: err instanceof Error ? err.message : 'Unexpected failure',
  });

  return c.json(
    {
      error: 'internal_error',
      message: 'An unexpected server error occurred.',
    },
    500
  );
}
