import { afterEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { errorResponseSchema } from '@kodakiko/contract';
import { API_MAX_BODY_BYTES } from '../../src/config/constants.ts';
import { AppError } from '../../src/domain/errors.ts';
import { ERROR_TEXT } from '../../src/text/errors.ts';
import { buildUnitApp } from './support.ts';

let app: FastifyInstance;

async function startApp(): Promise<void> {
  app = await buildUnitApp();
  app.get('/test/not-found', async () => {
    throw new AppError('NOT_FOUND');
  });
  app.get('/test/conflict', async () => {
    throw new AppError('REVISION_CONFLICT', { revision: 3 });
  });
  app.get('/test/boom', async () => {
    throw new Error('database password is hunter2');
  });
  app
    .withTypeProvider<ZodTypeProvider>()
    .post('/test/body', { schema: { body: z.object({ name: z.string() }) } }, async () => ({
      ok: true,
    }));
  app.post('/test/echo', async () => ({ ok: true }));
  app.get('/test/validation-error', async () => {
    throw Object.assign(new Error('invalid'), {
      validation: [
        {
          keyword: 'type',
          instancePath: '/name',
          schemaPath: '#/name',
          params: {},
          message: 'bad',
        },
      ],
    });
  });
  await app.ready();
}

afterEach(async () => {
  await app.close();
});

describe('error handling', () => {
  it('maps an AppError to its status, catalog message and request id', async () => {
    await startApp();
    const response = await app.inject({ method: 'GET', url: '/test/not-found' });
    expect(response.statusCode).toBe(404);
    const body = errorResponseSchema.parse(response.json());
    expect(body.error.code).toBe('NOT_FOUND');
    expect(body.error.message).toBe(ERROR_TEXT.NOT_FOUND);
    expect(body.error.details).toBeNull();
    expect(body.error.requestId).toBe(response.headers['x-request-id']);
  });

  it('passes AppError details through', async () => {
    await startApp();
    const response = await app.inject({ method: 'GET', url: '/test/conflict' });
    expect(response.statusCode).toBe(409);
    expect(response.json().error.details).toEqual({ revision: 3 });
  });

  it('answers 422 with issues for a Zod body violation', async () => {
    await startApp();
    const response = await app.inject({ method: 'POST', url: '/test/body', payload: { name: 5 } });
    expect(response.statusCode).toBe(422);
    const body = errorResponseSchema.parse(response.json());
    expect(body.error.code).toBe('VALIDATION_FAILED');
    const details = body.error.details as { issues: { path: unknown; message: string }[] };
    expect(details.issues.length).toBeGreaterThan(0);
    expect(Object.keys(details.issues[0] ?? {}).sort()).toEqual(['message', 'path']);
  });

  it('answers an unknown route with the 404 shape', async () => {
    await startApp();
    const response = await app.inject({ method: 'GET', url: '/nothing/here' });
    expect(response.statusCode).toBe(404);
    expect(errorResponseSchema.parse(response.json()).error.code).toBe('NOT_FOUND');
  });

  it('hides the message of an unexpected error', async () => {
    await startApp();
    const response = await app.inject({ method: 'GET', url: '/test/boom' });
    expect(response.statusCode).toBe(500);
    const body = errorResponseSchema.parse(response.json());
    expect(body.error.code).toBe('INTERNAL');
    expect(body.error.message).toBe(ERROR_TEXT.INTERNAL);
    expect(response.body).not.toContain('hunter2');
  });

  it('answers 413 for a body of limit plus one byte', async () => {
    await startApp();
    const response = await app.inject({
      method: 'POST',
      url: '/test/echo',
      headers: { 'content-type': 'application/json' },
      payload: 'x'.repeat(API_MAX_BODY_BYTES + 1),
    });
    expect(response.statusCode).toBe(413);
    expect(errorResponseSchema.parse(response.json()).error.code).toBe('PAYLOAD_TOO_LARGE');
  });

  it('maps any error carrying validation details to VALIDATION_FAILED', async () => {
    await startApp();
    const response = await app.inject({ method: 'GET', url: '/test/validation-error' });
    expect(response.statusCode).toBe(422);
    const body = errorResponseSchema.parse(response.json());
    expect(body.error.code).toBe('VALIDATION_FAILED');
    expect(body.error.details).toEqual({ issues: [{ path: ['name'], message: 'bad' }] });
  });

  it('answers a malformed URL with the error shape, request id and no-store', async () => {
    await startApp();
    const response = await app.inject({ method: 'GET', url: '/api/v1/%E0%A4%A' });
    expect(response.statusCode).toBe(400);
    const body = errorResponseSchema.parse(response.json());
    expect(body.error.code).toBe('MALFORMED_REQUEST');
    expect(body.error.requestId).toBe(response.headers['x-request-id']);
    expect(response.headers['cache-control']).toBe('no-store');
  });

  it('answers 400 for invalid JSON', async () => {
    await startApp();
    const response = await app.inject({
      method: 'POST',
      url: '/test/echo',
      headers: { 'content-type': 'application/json' },
      payload: '{not json',
    });
    expect(response.statusCode).toBe(400);
    expect(errorResponseSchema.parse(response.json()).error.code).toBe('MALFORMED_REQUEST');
  });
});
