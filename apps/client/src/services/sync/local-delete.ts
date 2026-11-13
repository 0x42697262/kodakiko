import type { FileStore } from '../../adapters/filestore/filestore';
import type { RowStore, RowStoreTx } from '../../adapters/rowstore/rowstore';
import { entryIdOfPayload } from './outbox-rows';

export async function removeMediaRows(tx: RowStoreTx, mediaId: string): Promise<readonly string[]> {
  const objects = await tx.objects.byMedia(mediaId);
  for (const object of objects) await tx.objects.delete(object.id);
  await tx.media.delete(mediaId);
  return objects.map((object) => object.id);
}

export async function removeEntryRows(tx: RowStoreTx, entryId: string): Promise<readonly string[]> {
  const objectIds: string[] = [];
  for (const media of await tx.media.byEntry(entryId)) {
    objectIds.push(...(await removeMediaRows(tx, media.id)));
  }
  for (const membership of await tx.memberships.byEntry(entryId)) {
    await tx.memberships.delete([membership.collectionId, membership.entryId]);
  }
  await tx.pins.delete(entryId);
  await tx.entries.delete(entryId);
  for (const row of await tx.outbox.all()) {
    if (entryIdOfPayload(row.payload) === entryId) await tx.outbox.delete(row.id);
  }
  return objectIds;
}

export async function deleteFiles(
  fileStore: { readonly delete: (objectId: string) => Promise<void> },
  objectIds: readonly string[],
): Promise<void> {
  for (const objectId of objectIds) await fileStore.delete(objectId);
}

export async function dropLocalCollection(
  rowStore: RowStore,
  fileStore: FileStore,
  collectionId: string,
): Promise<readonly string[]> {
  const changed = new Set<string>();
  const files: string[] = [];
  await rowStore.transaction(async (tx) => {
    for (const membership of await tx.memberships.byCollection(collectionId)) {
      await tx.memberships.delete([membership.collectionId, membership.entryId]);
      changed.add(membership.entryId);
      if ((await tx.memberships.byEntry(membership.entryId)).length === 0) {
        files.push(...(await removeEntryRows(tx, membership.entryId)));
      }
    }
    await tx.collections.delete(collectionId);
  });
  await deleteFiles(fileStore, files);
  return [...changed];
}
