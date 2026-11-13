import {
  encodeBytes,
  type LinkEntriesResponse,
  type LinkOpenResponse,
  type PublicLinkDto,
  type PublicLinkListResponse,
} from '@kodakiko/contract';
import type { PublicLinkRow } from '../../adapters/db/public-links-repository.ts';
import type { AuthContext } from '../../services/auth-service.ts';
import type { PublicLinksService } from '../../services/public-links-service.ts';
import { toEntryDto, toMediaDto } from './entry-mappers.ts';
import { toObjectDto } from './object-mappers.ts';

function toPublicLinkDto(row: PublicLinkRow): PublicLinkDto {
  return {
    linkId: row.id,
    collectionId: row.collectionId,
    expiresAt: row.expiresAt?.toISOString() ?? null,
    maxViews: row.maxViews,
    viewCount: row.viewCount,
    createdAt: row.createdAt.toISOString(),
  } as PublicLinkDto;
}

export function createPublicLinksController(links: PublicLinksService) {
  return {
    async create(auth: AuthContext, body: { collectionId: string; accessToken: Uint8Array; expiry: string }) {
      return toPublicLinkDto(await links.create(auth.userId, body));
    },
    async list(auth: AuthContext): Promise<PublicLinkListResponse> {
      return { items: (await links.list(auth.userId)).map(toPublicLinkDto) };
    },
    revoke: (auth: AuthContext, linkId: string) => links.revoke(auth.userId, linkId),
    async open(linkId: string, token: string | undefined): Promise<LinkOpenResponse> {
      const view = await links.open(linkId, token);
      return {
        collectionId: view.collectionId,
        metadataBlob: encodeBytes(view.metadataBlob),
        expiresAt: view.expiresAt?.toISOString() ?? null,
        maxViews: view.maxViews,
        viewToken: view.viewToken,
      } as LinkOpenResponse;
    },
    async entries(
      linkId: string,
      token: string | undefined,
      viewToken: string | undefined,
      query: { readonly cursor?: string | undefined; readonly limit?: number | undefined },
    ): Promise<LinkEntriesResponse> {
      const page = await links.entries(linkId, token, viewToken, query.cursor, query.limit);
      return {
        items: page.items.map((item) => ({
          entry: toEntryDto(item.entry),
          entryKeyByCollection: encodeBytes(item.entryKeyByCollection),
          media: item.media.map(toMediaDto),
          objects: item.objects.map(({ row, download }) => ({
            ...toObjectDto({ id: row.id, mediaId: row.mediaId ?? '', variant: row.variant, sizeBytes: row.sizeBytes, state: row.state }),
            download:
              download.kind === 'proxy'
                ? download
                : { kind: 'presigned' as const, url: download.url, expiresAt: download.expiresAt.toISOString() },
          })),
        })),
        nextCursor: page.nextCursor,
      } as LinkEntriesResponse;
    },
    content: links.content,
  };
}

export type PublicLinksController = ReturnType<typeof createPublicLinksController>;
