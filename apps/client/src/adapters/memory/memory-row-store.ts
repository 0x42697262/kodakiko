import type { z } from 'zod';
import { EN } from '../../text/en';
import type { StorageKey } from '../../keys/storage-keys';
import { decodeJson, encodeJson } from '../rowstore/rowstore-codec';
import type { RowStore } from '../rowstore/rowstore';

export function createMemoryRowStore(): RowStore {
  const accounts = new Map<string, Map<StorageKey, Uint8Array>>();
  let values: Map<StorageKey, Uint8Array> | null = null;

  function current(): Map<StorageKey, Uint8Array> {
    if (values === null) throw new Error(EN['rowstore.notOpen']);
    return values;
  }

  async function close(): Promise<void> {
    values = null;
  }

  return {
    open: async (accountKey) => {
      values = accounts.get(accountKey) ?? new Map<StorageKey, Uint8Array>();
      accounts.set(accountKey, values);
    },
    close,
    destroy: async (accountKey) => {
      accounts.delete(accountKey);
      await close();
    },
    transaction: () => Promise.reject(new Error(EN['testing.notSupported'])),
    kvGet: async (key) => current().get(key)?.slice() ?? null,
    kvGetJson: async <T>(key: StorageKey, schema: z.ZodType<T>) => {
      const bytes = current().get(key);
      return bytes === undefined ? null : decodeJson(bytes, schema);
    },
    kvSet: async (key, value) => {
      current().set(key, value.slice());
    },
    kvSetJson: async (key, value) => {
      current().set(key, encodeJson(value));
    },
    kvDelete: async (key) => {
      current().delete(key);
    },
  };
}
