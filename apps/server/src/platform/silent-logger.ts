import type { FastifyBaseLogger } from 'fastify';

export type AuthLogger = Pick<FastifyBaseLogger, 'info' | 'warn'>;

export const silentLogger: AuthLogger = Object.freeze({
  info: () => undefined,
  warn: () => undefined,
});
