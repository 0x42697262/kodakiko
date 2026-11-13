import {
  encodeBytes,
  type CollectionCreateRequest,
  type CollectionDto,
  type PublicKeyResponse,
  type ShareListResponse,
} from '@kodakiko/contract';
import type { AuthContext } from '../../services/auth-service.ts';
import type { SharingService } from '../../services/sharing-service.ts';
import { bufferOf, toCollectionDto } from './entry-mappers.ts';

export function createSharingController(sharing: SharingService) {
  return {
    async create(
      auth: AuthContext,
      body: CollectionCreateRequest,
    ): Promise<{ readonly collection: CollectionDto; readonly created: boolean }> {
      const result = await sharing.createCollection(auth.userId, {
        id: body.id,
        kind: body.kind,
        keyByOwner: bufferOf(body.keyByOwner),
        metadataBlob: bufferOf(body.metadataBlob),
      });
      return { collection: toCollectionDto(result.value), created: result.created };
    },

    remove: (auth: AuthContext, id: string) => sharing.deleteCollection(auth.userId, id),

    addEntry: (auth: AuthContext, id: string, entryId: string, wrap: string) =>
      sharing.addEntry(auth.userId, id, entryId, bufferOf(wrap)),

    removeEntry: (auth: AuthContext, id: string, entryId: string) => sharing.removeEntry(auth.userId, id, entryId),

    share: (auth: AuthContext, id: string, recipientUserId: string, sealed: string) =>
      sharing.share(auth.userId, id, recipientUserId, bufferOf(sealed)),

    async listShares(auth: AuthContext, id: string): Promise<ShareListResponse> {
      const items = await sharing.listShares(auth.userId, id);
      return {
        items: items.map((item) => ({
          recipientUserId: item.recipientUserId,
          recipientUsername: item.recipientUsername,
          createdAt: item.createdAt.toISOString(),
        })),
      } as ShareListResponse;
    },

    unshare: (auth: AuthContext, id: string, recipientUserId: string) =>
      sharing.unshare(auth.userId, id, recipientUserId),

    async publicKey(username: string): Promise<PublicKeyResponse> {
      const found = await sharing.publicKey(username);
      return {
        userId: found.userId,
        username: found.username,
        publicKey: encodeBytes(found.publicKey),
      } as PublicKeyResponse;
    },
  };
}

export type SharingController = ReturnType<typeof createSharingController>;
