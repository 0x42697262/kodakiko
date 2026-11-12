import type { LocalEntry } from '@kodakiko/client/test-support';
import { signInDevice, signUpDevice, type Device } from './device.ts';
import { createEntryLocally, syncUntilIdle, type EntryInput } from './entries.ts';
import type { Harness } from './harness.ts';

export type Pair = { readonly a: Device; readonly b: Device };

export async function startPair(harness: Harness): Promise<Pair> {
  const a = await signUpDevice(harness, 'A');
  await syncUntilIdle(a);
  const b = await signInDevice(harness, 'B', a.account);
  await syncUntilIdle(b);
  return { a, b };
}

export async function createSyncedEntry(device: Device, input: EntryInput): Promise<LocalEntry> {
  const entry = await createEntryLocally(device, input);
  await syncUntilIdle(device);
  return entry;
}

export function mediaIdAt(entry: LocalEntry, position: number): string {
  const mediaId = entry.mediaIds[position];
  if (mediaId === undefined) throw new Error(`entry ${entry.entryId} has no media at ${position}`);
  return mediaId;
}
