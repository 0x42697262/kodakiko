import type {
  CollectionDto,
  CollectionListQuery,
  CollectionListResponse,
  CollectionPatchRequest,
  DiffPage,
  SyncStateResponse,
} from '@kodakiko/contract';
import type { AuthContext } from '../../services/auth-service.ts';
import type { CollectionsService } from '../../services/collections-service.ts';
import type { SyncService } from '../../services/sync-service.ts';
import {
  bufferOf,
  toCollectionDto,
  toCollectionListResponse,
  toDiffPage,
  toSyncStateResponse,
} from './entry-mappers.ts';

export function createCollectionsController(collections: CollectionsService, sync: SyncService) {
  return {
    async list(auth: AuthContext, query: CollectionListQuery): Promise<CollectionListResponse> {
      return toCollectionListResponse(
        await collections.list(auth.userId, query.cursor, query.limit),
      );
    },

    async get(auth: AuthContext, id: string): Promise<CollectionDto> {
      return toCollectionDto(await collections.get(auth.userId, id));
    },

    async rename(
      auth: AuthContext,
      id: string,
      body: CollectionPatchRequest,
    ): Promise<CollectionDto> {
      return toCollectionDto(
        await collections.rename(auth.userId, id, bufferOf(body.metadataBlob)),
      );
    },

    async diff(
      auth: AuthContext,
      id: string,
      query: { readonly since: number; readonly limit?: number | undefined },
    ): Promise<DiffPage> {
      return toDiffPage(await sync.diff(auth.userId, id, query.since, query.limit));
    },

    async syncState(auth: AuthContext): Promise<SyncStateResponse> {
      return toSyncStateResponse(await sync.state(auth.userId));
    },
  };
}

export type CollectionsController = ReturnType<typeof createCollectionsController>;
