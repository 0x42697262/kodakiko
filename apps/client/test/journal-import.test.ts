import { EntryMetadataV1 } from '@kodakiko/contract';
import { createKdkxWriter, deriveKdkxKey, planEntryExport, type Sodium } from '@kodakiko/core';
import { strToU8, zipSync } from 'fflate';
import { beforeAll, describe, expect, it } from 'vitest';
import { createJournalImport, ImportPassphraseError } from '../src/services/import/journal-import';
import { createNodeSodium } from './support/node-sodium';

const FAST = { opsLimit: 2, memLimitBytes: 8_388_608 };
const MEDIA = {
  kind: 'photo' as const, mimeType: 'image/jpeg', width: 10, height: 10, durationMs: null, originalPlaintextBytes: 3,
  thumbnail: { width: 10, height: 10, mimeType: 'image/webp' as const, plaintextBytes: 10 }, capturedAtUtc: null,
};

let sodium: Sodium;

beforeAll(async () => {
  sodium = await createNodeSodium();
});

function noteFor(id: string, patch: Record<string, unknown>) {
  const metadata = EntryMetadataV1.parse({
    schemaVersion: 1, takenAtUtc: '2026-03-01T10:00:00.000Z', utcOffsetMinutes: 60, timeZone: null, note: 'hello', tags: ['trip'],
    location: { latitude: 1.5, longitude: 2.5, accuracyMeters: null, source: 'exif' },
    media: [{ ...MEDIA, mediaId: 'mmmmmmmmmmmmmmmmmmmm01', originalFileName: 'beach.jpg' }],
    createdAtUtc: '2026-03-01T10:00:00.000Z', editedAtUtc: '2026-03-01T10:00:00.000Z', ...patch,
  });
  return planEntryExport(id, metadata);
}

function exportZip(plans: ReturnType<typeof noteFor>[]): Uint8Array {
  const files: Record<string, Uint8Array> = { 'README.txt': strToU8('readme') };
  for (const plan of plans) {
    files[plan.noteFile] = strToU8(plan.noteText);
    for (const media of plan.media) files[`${plan.folder}${media.fileName}`] = Uint8Array.of(1, 2, 3);
  }
  return zipSync(files);
}

async function encrypt(bytes: Uint8Array, passphrase: string): Promise<Uint8Array> {
  const params = { salt: sodium.randomBytes(16), ...FAST };
  const writer = createKdkxWriter(sodium, await deriveKdkxKey(sodium, passphrase, params), params);
  const parts = [writer.header, ...writer.push(bytes), writer.finish()];
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let at = 0;
  for (const part of parts) {
    out.set(part, at);
    at += part.length;
  }
  return out;
}

function fakes(existing: { takenAtUtc: string; mediaCount: number }[] = []) {
  const created: { input: unknown; files: string[] }[] = [];
  const favorites: string[] = [];
  const captions: { entryId: string; captions: Readonly<Record<string, string>> }[] = [];
  let next = 0;
  const service = createJournalImport({
    sodium,
    importMedia: async (file) => ({ ok: true, media: { mediaId: `m${(next += 1)}`, fileName: file.fileName } as never }),
    createEntry: async (input, items) => {
      created.push({ input, files: items.map((item) => (item as unknown as { fileName: string }).fileName) });
      return `e${created.length}`;
    },
    setFavorite: async (ids) => {
      favorites.push(...ids);
    },
    setCaptions: async (entryId, values) => {
      captions.push({ entryId, captions: values });
    },
    existingEntries: () => existing,
  });
  return { service, created, favorites, captions };
}

describe('journal import (spec 23 §7)', () => {
  it('creates an entry per entry.md with its time, offset, tags, place, note and media in order', async () => {
    const { service, created, favorites } = fakes();
    const summary = await service.run([{ name: 'kodakiko-export-01.zip', bytes: exportZip([noteFor('A', { favorite: true })]) }], null, () => undefined);
    expect(summary).toEqual({ entries: 1, media: 1, duplicates: 0, failed: [] });
    expect(created[0]).toEqual({
      input: {
        note: 'hello',
        tags: ['trip'],
        time: { takenAtUtc: '2026-03-01T10:00:00.000Z', utcOffsetMinutes: 60 },
        location: { latitude: 1.5, longitude: 2.5, accuracyMeters: null, source: 'exif' },
      },
      files: ['beach.jpg'],
    });
    expect(favorites).toEqual(['e1']);
  });

  it('opens encrypted parts with the passphrase and refuses a wrong or missing one', async () => {
    const encrypted = await encrypt(exportZip([noteFor('A', {})]), 'secret words here');
    const { service, created } = fakes();
    await expect(service.run([{ name: 'part.zip.kdkx', bytes: encrypted }], null, () => undefined)).rejects.toBeInstanceOf(ImportPassphraseError);
    await expect(service.run([{ name: 'part.zip.kdkx', bytes: encrypted }], 'wrong words', () => undefined)).rejects.toBeInstanceOf(ImportPassphraseError);
    expect((await service.run([{ name: 'part.zip.kdkx', bytes: encrypted }], 'secret words here', () => undefined)).entries).toBe(1);
    expect(created).toHaveLength(1);
  });

  it('skips entries that already exist with the same time and number of items, and reports missing files', async () => {
    const { service, created } = fakes([{ takenAtUtc: '2026-03-01T10:00:00.000Z', mediaCount: 1 }]);
    const other = noteFor('B', { takenAtUtc: '2026-04-01T10:00:00.000Z' });
    const zip = zipSync({ [other.noteFile]: strToU8(other.noteText) });
    const summary = await service.run(
      [
        { name: 'a.zip', bytes: exportZip([noteFor('A', {})]) },
        { name: 'b.zip', bytes: zip },
      ],
      null,
      () => undefined,
    );
    expect(summary.duplicates).toBe(1);
    expect(summary.entries).toBe(0);
    expect(summary.failed).toEqual([{ path: other.noteFile, reason: 'missingMedia' }]);
    expect(created).toHaveLength(0);
  });

  it('restores captions on the imported media (spec 25)', async () => {
    const { service, captions } = fakes();
    const captioned = noteFor('C', { media: [{ ...MEDIA, mediaId: 'mmmmmmmmmmmmmmmmmmmm01', originalFileName: 'beach.jpg', caption: 'Low tide' }] });
    await service.run([{ name: 'c.zip', bytes: exportZip([captioned]) }], null, () => undefined);
    expect(captions).toEqual([{ entryId: 'e1', captions: { m1: 'Low tide' } }]);
  });
});
