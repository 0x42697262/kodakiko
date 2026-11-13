import {
  ROUTES,
  collectionDto,
  publicKeyResponse,
  shareListResponse,
  type CollectionCreateRequest,
} from '@kodakiko/contract';
import { z } from 'zod';
import type { ApiCall } from '../../adapters/http/http';

const empty = z.unknown();

export const SHARING_CALLS = Object.freeze({
  create: (body: CollectionCreateRequest): ApiCall<z.infer<typeof collectionDto>> => ({
    method: 'POST',
    path: ROUTES.collections(),
    body,
    responseSchema: collectionDto,
    auth: 'session',
    idempotent: true,
  }),
  rename: (id: string, metadataBlob: string): ApiCall<z.infer<typeof collectionDto>> => ({
    method: 'PATCH',
    path: ROUTES.collection(id),
    body: { metadataBlob },
    responseSchema: collectionDto,
    auth: 'session',
    idempotent: true,
  }),
  remove: (id: string): ApiCall<unknown> => ({
    method: 'DELETE',
    path: ROUTES.collection(id),
    responseSchema: empty,
    auth: 'session',
    idempotent: true,
  }),
  addEntry: (id: string, entryId: string, entryKeyByCollection: string): ApiCall<unknown> => ({
    method: 'POST',
    path: ROUTES.collectionEntries(id),
    body: { entryId, entryKeyByCollection },
    responseSchema: empty,
    auth: 'session',
    idempotent: true,
  }),
  removeEntry: (id: string, entryId: string): ApiCall<unknown> => ({
    method: 'DELETE',
    path: ROUTES.collectionEntry(id, entryId),
    responseSchema: empty,
    auth: 'session',
    idempotent: true,
  }),
  publicKey: (username: string): ApiCall<z.infer<typeof publicKeyResponse>> => ({
    method: 'GET',
    path: ROUTES.userPublicKey(username),
    responseSchema: publicKeyResponse,
    auth: 'session',
    idempotent: true,
  }),
  share: (id: string, recipientUserId: string, sealedCollectionKey: string): ApiCall<unknown> => ({
    method: 'POST',
    path: ROUTES.collectionShares(id),
    body: { recipientUserId, sealedCollectionKey },
    responseSchema: empty,
    auth: 'session',
    idempotent: true,
  }),
  listShares: (id: string): ApiCall<z.infer<typeof shareListResponse>> => ({
    method: 'GET',
    path: ROUTES.collectionShares(id),
    responseSchema: shareListResponse,
    auth: 'session',
    idempotent: true,
  }),
  unshare: (id: string, recipientUserId: string): ApiCall<unknown> => ({
    method: 'DELETE',
    path: ROUTES.collectionShare(id, recipientUserId),
    responseSchema: empty,
    auth: 'session',
    idempotent: true,
  }),
});
