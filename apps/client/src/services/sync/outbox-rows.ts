import type { OutboxRow } from '../../adapters/rowstore/rowstore';

export function entryIdOfPayload(payload: unknown): string | null {
  if (typeof payload !== 'object' || payload === null) return null;
  const entryId = (payload as Record<string, unknown>)['entryId'];
  return typeof entryId === 'string' ? entryId : null;
}

export function mediaIdOfPayload(payload: unknown): string | null {
  if (typeof payload !== 'object' || payload === null) return null;
  const mediaId = (payload as Record<string, unknown>)['mediaId'];
  return typeof mediaId === 'string' ? mediaId : null;
}

export function isUploadItem(row: OutboxRow): boolean {
  return row.kind === 'createEntry' || row.kind === 'addMedia';
}
