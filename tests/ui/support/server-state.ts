import {
  BEARER_PREFIX,
  COLLECTIONS_PATH,
  DIFF_PAGE_LIMIT,
  DIFF_PATH_SUFFIX,
  SYNC_STATE_PATH,
  WEB_ORIGIN,
} from './constants.ts';
import type { SeededAccount } from './seed.ts';
import { STACK_TEXT } from './text.ts';

const HTTP_OK = 200;
const HOME_KIND = 'home';

type SyncStateBody = { readonly collections: readonly { id: string; kind: string }[] };

type DiffBody = {
  readonly entries: readonly { id: string; trashedAt: string | null }[];
  readonly media: readonly { id: string; entryId: string }[];
  readonly objects: readonly { id: string }[];
};

export type ServerJournal = {
  readonly entryCount: number;
  readonly trashedCount: number;
  readonly mediaCount: number;
  readonly objectCount: number;
};

async function getJson<Body>(account: SeededAccount, path: string): Promise<Body> {
  const response = await fetch(`${WEB_ORIGIN}${path}`, {
    headers: { Authorization: `${BEARER_PREFIX}${account.sessionToken}` },
  });
  if (response.status !== HTTP_OK) {
    throw new Error(STACK_TEXT.serverRequestFailed(path, response.status));
  }
  return (await response.json()) as Body;
}

export async function readServerJournal(account: SeededAccount): Promise<ServerJournal> {
  const state = await getJson<SyncStateBody>(account, SYNC_STATE_PATH);
  const home = state.collections.find((collection) => collection.kind === HOME_KIND);
  if (home === undefined) throw new Error(STACK_TEXT.homeCollectionMissing);
  const diff = await getJson<DiffBody>(
    account,
    `${COLLECTIONS_PATH}/${home.id}${DIFF_PATH_SUFFIX}?since=0&limit=${DIFF_PAGE_LIMIT}`,
  );
  return {
    entryCount: diff.entries.length,
    trashedCount: diff.entries.filter((entry) => entry.trashedAt !== null).length,
    mediaCount: diff.media.length,
    objectCount: diff.objects.length,
  };
}
