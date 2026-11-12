import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { z } from 'zod';
import { ROUTE_PATTERNS } from '@kodakiko/contract';
import { HTTP_STATUS } from '../../config/constants.ts';
import { failedChecks, type ReadinessCheck } from '../../services/readiness.ts';
import { LOG_EVENTS } from '../../text/events.ts';

const okSchema = z.object({ status: z.literal('ok') });
const unavailableSchema = z.object({
  status: z.literal('unavailable'),
  failed: z.array(z.string()),
});

export type HealthRouteOptions = { readonly readinessChecks: readonly ReadinessCheck[] };

export function registerHealthRoutes(app: FastifyInstance, options: HealthRouteOptions): void {
  const typed = app.withTypeProvider<ZodTypeProvider>();
  typed.get(ROUTE_PATTERNS.healthLive, { schema: { response: { 200: okSchema } } }, async () => ({
    status: 'ok' as const,
  }));
  typed.get(
    ROUTE_PATTERNS.healthReady,
    {
      schema: {
        response: { 200: okSchema, [HTTP_STATUS.serviceUnavailable]: unavailableSchema },
      },
    },
    async (request, reply) => {
      const failed = await failedChecks(options.readinessChecks);
      if (failed.length === 0) return { status: 'ok' as const };
      request.log.warn({ event: LOG_EVENTS.healthNotReady, failed });
      return reply
        .status(HTTP_STATUS.serviceUnavailable)
        .send({ status: 'unavailable' as const, failed });
    },
  );
}
