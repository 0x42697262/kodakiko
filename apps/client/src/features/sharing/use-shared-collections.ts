import { useEffect, useState, useSyncExternalStore } from 'react';
import { useAppContext } from '../../composition/app-context';
import { EMPTY_GALLERY_FILTER } from '../gallery/filter-model';
import { useIndexVersion } from '../gallery/hooks';

export type SharedCollection = {
  readonly id: string;
  readonly role: 'owner' | 'viewer';
  readonly ownerUsername: string;
  readonly name: string;
  readonly entryCount: number;
};

export function useSharedCollections(): readonly SharedCollection[] | null {
  const { services, rowStore } = useAppContext();
  const version = useIndexVersion();
  const sync = useSyncExternalStore(services.sync.subscribe, services.sync.status);
  const [collections, setCollections] = useState<readonly SharedCollection[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const rows = (await rowStore.transaction((tx) => tx.collections.all())).filter((row) => row.kind === 'shared');
      const index = services.index.index();
      const loaded: SharedCollection[] = [];
      for (const row of rows) {
        const sections = index?.query({ ...EMPTY_GALLERY_FILTER, scope: { kind: 'collection', collectionId: row.id } }) ?? [];
        loaded.push({
          id: row.id,
          role: row.role,
          ownerUsername: row.ownerUsername,
          name: await services.collectionNames.get(row.id),
          entryCount: sections.reduce((sum, section) => sum + section.entryIds.length, 0),
        });
      }
      loaded.sort((left, right) => left.name.localeCompare(right.name));
      if (!cancelled) setCollections(loaded);
    })().catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [rowStore, services.index, services.collectionNames, version, sync.lastSuccessAt]);

  return collections;
}

export function useSharedCollection(collectionId: string): SharedCollection | null | undefined {
  const all = useSharedCollections();
  if (all === null) return undefined;
  return all.find((collection) => collection.id === collectionId) ?? null;
}
