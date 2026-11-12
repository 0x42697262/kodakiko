import type {
  EntryCreateRequest,
  EntryDto,
  EntryPatchRequest,
  MediaCreateRequest,
  MediaDto,
} from '@kodakiko/contract';
import type { AuthContext } from '../../services/auth-service.ts';
import type { EntriesService } from '../../services/entries-service.ts';
import { bufferOf, toCreateEntryCommand, toEntryDto, toMediaDto } from './entry-mappers.ts';

export type CreatedEntryResponse = { readonly entry: EntryDto; readonly created: boolean };
export type AddedMediaResponse = { readonly media: MediaDto; readonly created: boolean };

export function createEntriesController(service: EntriesService) {
  return {
    async create(auth: AuthContext, body: EntryCreateRequest): Promise<CreatedEntryResponse> {
      const result = await service.create(auth.userId, toCreateEntryCommand(body));
      return { entry: toEntryDto(result.entry), created: result.created };
    },

    async get(auth: AuthContext, id: string): Promise<EntryDto> {
      return toEntryDto(await service.get(auth.userId, id));
    },

    async patch(auth: AuthContext, id: string, body: EntryPatchRequest): Promise<EntryDto> {
      const command = { metadataBlob: bufferOf(body.metadataBlob), baseRevision: body.baseRevision };
      return toEntryDto(await service.patch(auth.userId, id, command));
    },

    async trash(auth: AuthContext, id: string): Promise<EntryDto> {
      return toEntryDto(await service.trash(auth.userId, id));
    },

    async restore(auth: AuthContext, id: string): Promise<EntryDto> {
      return toEntryDto(await service.restore(auth.userId, id));
    },

    async remove(auth: AuthContext, id: string): Promise<void> {
      await service.remove(auth.userId, id);
    },

    async addMedia(
      auth: AuthContext,
      entryId: string,
      body: MediaCreateRequest,
    ): Promise<AddedMediaResponse> {
      const command = { mediaId: body.mediaId, mediaKeyByEntry: bufferOf(body.mediaKeyByEntry) };
      const result = await service.addMedia(auth.userId, entryId, command);
      return { media: toMediaDto(result.media), created: result.created };
    },

    async removeMedia(auth: AuthContext, mediaId: string): Promise<void> {
      await service.removeMedia(auth.userId, mediaId);
    },
  };
}

export type EntriesController = ReturnType<typeof createEntriesController>;
