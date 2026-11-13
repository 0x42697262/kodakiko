import { ROUTES, downloadResponse } from '@kodakiko/contract';
import type { z } from 'zod';
import type { ApiCall } from '../../adapters/http/http';

export const OBJECT_CALLS = Object.freeze({
  download: (objectId: string): ApiCall<z.infer<typeof downloadResponse>> => ({
    method: 'GET',
    path: ROUTES.objectDownload(objectId),
    responseSchema: downloadResponse,
    auth: 'session',
    idempotent: true,
  }),
});
