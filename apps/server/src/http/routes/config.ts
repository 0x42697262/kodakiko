import type { FastifyInstance } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { publicConfigSchema, ROUTE_PATTERNS } from '@kodakiko/contract';
import type { Settings } from '../../config/settings.ts';
import { configController } from '../controllers/config-controller.ts';
import type { RateLimitFor } from '../rate-limits.ts';

export type ConfigRouteOptions = {
  readonly settings: Settings;
  readonly rateLimitFor: RateLimitFor;
};

export function registerConfigRoutes(app: FastifyInstance, options: ConfigRouteOptions): void {
  app
    .withTypeProvider<ZodTypeProvider>()
    .get(
      ROUTE_PATTERNS.config,
      { config: options.rateLimitFor('users'), schema: { response: { 200: publicConfigSchema } } },
      async () => configController(options.settings),
    );
}
