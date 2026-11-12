import type {
  DownloadResponse,
  ObjectDto,
  PartsResponse,
  PresignRequest,
  PresignResponse,
} from '@kodakiko/contract';
import type { AuthContext } from '../../services/auth-service.ts';
import type { ObjectsService } from '../../services/objects-service.ts';
import {
  toDownloadResponse,
  toObjectDto,
  toPartsResponse,
  toPresignResponse,
} from './object-mappers.ts';

export function createObjectsController(service: ObjectsService) {
  return {
    async presign(auth: AuthContext, body: PresignRequest): Promise<PresignResponse> {
      return toPresignResponse(await service.presign(auth.userId, body));
    },

    async parts(auth: AuthContext, id: string): Promise<PartsResponse> {
      return toPartsResponse(await service.parts(auth.userId, id));
    },

    async confirm(auth: AuthContext, id: string): Promise<ObjectDto> {
      return toObjectDto(await service.confirm(auth.userId, id));
    },

    async download(auth: AuthContext, id: string): Promise<DownloadResponse> {
      return toDownloadResponse(await service.download(auth.userId, id));
    },
  };
}

export type ObjectsController = ReturnType<typeof createObjectsController>;
