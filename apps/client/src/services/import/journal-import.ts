import {
  decryptKdkx,
  deriveKdkxKey,
  EXPORT_NOTE_FILE,
  isKdkx,
  parseEntryNote,
  readKdkxParams,
  wipe,
  type Sodium,
} from '@kodakiko/core';
import { unzipSync } from 'fflate';
import type { CreateFromTrayInput } from '../entries/entries-service';
import type { ImportedMedia, ImportOutcome } from '../media/import-service';

export type ImportFile = { readonly name: string; readonly bytes: Uint8Array };
export type ImportFailure = { readonly path: string; readonly reason: 'unreadable' | 'missingMedia' | 'refused' };
export type ImportSummary = { readonly entries: number; readonly media: number; readonly duplicates: number; readonly failed: readonly ImportFailure[] };
export type ImportProgress = { readonly done: number; readonly total: number };

export class ImportPassphraseError extends Error {}

export type JournalImportDeps = {
  readonly sodium: Sodium;
  readonly importMedia: (file: { readonly bytes: Uint8Array; readonly fileName: string }, position: number) => Promise<ImportOutcome>;
  readonly createEntry: (input: CreateFromTrayInput, items: readonly ImportedMedia[]) => Promise<string>;
  readonly setFavorite: (entryIds: readonly string[], favorite: boolean) => Promise<unknown>;
  readonly existingEntries: () => readonly { readonly takenAtUtc: string; readonly mediaCount: number }[];
};

const NOTE_SUFFIX = `/${EXPORT_NOTE_FILE}`;

async function openPart(sodium: Sodium, file: ImportFile, passphrase: string | null): Promise<Uint8Array> {
  if (!isKdkx(file.bytes)) return file.bytes;
  if (passphrase === null || passphrase === '') throw new ImportPassphraseError(file.name);
  const key = await deriveKdkxKey(sodium, passphrase, readKdkxParams(file.bytes));
  try {
    return decryptKdkx(sodium, key, file.bytes);
  } catch {
    throw new ImportPassphraseError(file.name);
  } finally {
    wipe(key);
  }
}

export function createJournalImport(deps: JournalImportDeps) {
  async function run(files: readonly ImportFile[], passphrase: string | null, onProgress: (progress: ImportProgress) => void): Promise<ImportSummary> {
    const contents = new Map<string, Uint8Array>();
    for (const file of files) {
      const zip = await openPart(deps.sodium, file, passphrase);
      for (const [path, bytes] of Object.entries(unzipSync(zip))) contents.set(path, bytes);
    }
    const notes = [...contents.keys()].filter((path) => path.endsWith(NOTE_SUFFIX)).sort();
    const seen = new Set(deps.existingEntries().map((entry) => `${entry.takenAtUtc}#${entry.mediaCount}`));
    const failed: ImportFailure[] = [];
    let entries = 0;
    let media = 0;
    let duplicates = 0;
    for (const [done, path] of notes.entries()) {
      onProgress({ done, total: notes.length });
      let parsed;
      try {
        parsed = parseEntryNote(new TextDecoder().decode(contents.get(path)));
      } catch {
        failed.push({ path, reason: 'unreadable' });
        continue;
      }
      if (seen.has(`${parsed.takenAtUtc}#${parsed.media.length}`)) {
        duplicates += 1;
        continue;
      }
      const folder = path.slice(0, path.length - EXPORT_NOTE_FILE.length);
      const items: ImportedMedia[] = [];
      let problem: ImportFailure['reason'] | null = null;
      for (const [position, item] of parsed.media.entries()) {
        const bytes = contents.get(`${folder}${item.file}`);
        if (bytes === undefined) {
          problem = 'missingMedia';
          continue;
        }
        const outcome = await deps.importMedia({ bytes, fileName: item.originalName ?? item.file }, position);
        if (outcome.ok) items.push(outcome.media);
        else problem = 'refused';
      }
      if (items.length === 0) {
        failed.push({ path, reason: problem ?? 'missingMedia' });
        continue;
      }
      const entryId = await deps.createEntry(
        {
          note: parsed.note,
          tags: parsed.tags,
          time: { takenAtUtc: parsed.takenAtUtc, utcOffsetMinutes: parsed.utcOffsetMinutes },
          location:
            parsed.location === null
              ? null
              : { latitude: parsed.location.latitude, longitude: parsed.location.longitude, accuracyMeters: null, source: parsed.location.source === 'device' ? 'device' : 'exif' },
        },
        items,
      );
      if (parsed.favorite) await deps.setFavorite([entryId], true);
      seen.add(`${parsed.takenAtUtc}#${parsed.media.length}`);
      entries += 1;
      media += items.length;
      if (problem !== null) failed.push({ path, reason: problem });
    }
    onProgress({ done: notes.length, total: notes.length });
    return { entries, media, duplicates, failed };
  }
  return { run };
}

export type JournalImport = ReturnType<typeof createJournalImport>;
