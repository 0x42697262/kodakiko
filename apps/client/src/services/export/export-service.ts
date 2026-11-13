import {
  EXPORT_MISSING_FILE,
  EXPORT_PART_MAX_FILES,
  EXPORT_README_FILE,
  EXPORT_TEXT,
  STREAM_HEADER_BYTES,
  createStreamDecryptor,
  partFileName,
  planEntryExport,
  planParts,
  wipe,
  type ExportEntryPlan,
  type PlannedFile,
  type Sodium,
} from '@kodakiko/core';
import type { EntryMetadataV1 } from '@kodakiko/contract';
import type { z } from 'zod';
import type { Clock } from '../../adapters/clock';
import type { FileStore } from '../../adapters/filestore/filestore';
import type { RowStore } from '../../adapters/rowstore/rowstore';
import type { EntryKeys } from '../keys/entry-keys';
import type { ArchiveWriter, PartSink } from './archive';

export type { ArchiveFile, ArchiveWriter, PartSink } from './archive';

type Metadata = z.infer<typeof EntryMetadataV1>;

const DATE_CHARS = 10;
const UTF8 = new TextEncoder();

export type ExportProgress = {
  readonly phase: 'planning' | 'downloading' | 'writing' | 'done';
  readonly entriesDone: number;
  readonly entriesTotal: number;
  readonly bytesWritten: number;
  readonly partNumber: number;
  readonly partsTotal: number;
};

export type ExportSummary = {
  readonly parts: number;
  readonly entries: number;
  readonly mediaFiles: number;
  readonly bytes: number;
  readonly skippedUnreadable: number;
  readonly skippedMissing: number;
  readonly skippedTooLarge: number;
};

export type ExportDestination = {
  readonly partMaxBytes: number;
  readonly createPart: (name: string) => Promise<PartSink>;
};

export class ExportCancelledError extends Error {}

export type ExportServiceDeps = {
  readonly entryIds: () => Promise<readonly string[]>;
  readonly readMetadata: (entryId: string) => Promise<Metadata>;
  readonly entryKeys: Pick<EntryKeys, 'variantKey'>;
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly fetchObject: (objectId: string) => Promise<void>;
  readonly enforceCap: () => Promise<void>;
  readonly sodium: Sodium;
  readonly autoLock: { readonly pause: () => void; readonly resume: () => void };
  readonly clock: Clock;
  readonly createArchiveWriter: (sink: PartSink) => ArchiveWriter;
};

type FileJob = PlannedFile & {
  readonly kind: 'note' | 'media';
  readonly plan: ExportEntryPlan;
  readonly fileName: string;
  readonly mediaId: string | null;
};

type Skipped = { readonly folder: string; readonly file: string; readonly reason: string };

export type ExportService = {
  readonly run: (destination: ExportDestination, onProgress: (progress: ExportProgress) => void) => Promise<ExportSummary>;
  readonly cancel: () => void;
};

export function createExportService(deps: ExportServiceDeps): ExportService {
  let cancelled = false;

  function check(): void {
    if (cancelled) throw new ExportCancelledError();
  }

  async function writeMedia(entryId: string, job: FileJob, writer: ArchiveWriter): Promise<number | 'missing'> {
    const rows = await deps.rowStore.transaction((tx) => tx.objects.byMedia(job.mediaId ?? ''));
    let row = rows.find((candidate) => candidate.variant === 'original');
    if (row === undefined || row.localState === 'unavailable') return 'missing';
    if (row.localState !== 'present') {
      try {
        await deps.fetchObject(row.id);
      } catch {
        return 'missing';
      }
      row = (await deps.rowStore.transaction((tx) => tx.objects.byMedia(job.mediaId ?? ''))).find((candidate) => candidate.variant === 'original');
      if (row === undefined || row.localState !== 'present') return 'missing';
    }
    const key = await deps.entryKeys.variantKey(entryId, job.mediaId ?? '', 'original');
    try {
      const header = await deps.fileStore.readRange(row.id, 0, STREAM_HEADER_BYTES);
      const decryptor = createStreamDecryptor(deps.sodium, key, header, row.plaintextSize);
      const file = writer.addFile(`${job.plan.folder}${job.fileName}`, { compress: false, mtime: job.plan.mtime });
      const step = decryptor.layout.chunkCiphertextSize;
      let written = 0;
      for (let index = 0; index < decryptor.layout.chunkCount; index += 1) {
        check();
        const offset = STREAM_HEADER_BYTES + index * step;
        const chunk = await deps.fileStore.readRange(row.id, offset, Math.min(step, row.sizeBytes - offset));
        const plain = decryptor.decryptNext(chunk);
        written += plain.length;
        await file.push(plain, index === decryptor.layout.chunkCount - 1);
      }
      decryptor.finish();
      return written;
    } finally {
      wipe(key);
    }
  }

  async function run(destination: ExportDestination, onProgress: (progress: ExportProgress) => void): Promise<ExportSummary> {
    cancelled = false;
    deps.autoLock.pause();
    let sink: PartSink | null = null;
    try {
      onProgress({ phase: 'planning', entriesDone: 0, entriesTotal: 0, bytesWritten: 0, partNumber: 0, partsTotal: 0 });
      const ids = await deps.entryIds();
      const entries: { entryId: string; metadata: Metadata; plan: ExportEntryPlan }[] = [];
      let skippedUnreadable = 0;
      for (const entryId of ids) {
        try {
          const metadata = await deps.readMetadata(entryId);
          entries.push({ entryId, metadata, plan: planEntryExport(entryId, metadata) });
        } catch {
          skippedUnreadable += 1;
        }
      }
      entries.sort((left, right) => Date.parse(left.metadata.takenAtUtc) - Date.parse(right.metadata.takenAtUtc));
      const skipped: Skipped[] = [];
      let skippedTooLarge = 0;
      const jobs: FileJob[] = [];
      for (const { entryId, plan } of entries) {
        jobs.push({ entryId, path: plan.noteFile, bytes: UTF8.encode(plan.noteText).length, kind: 'note', plan, fileName: '', mediaId: null });
        for (const media of plan.media) {
          if (media.plaintextBytes > destination.partMaxBytes) {
            skippedTooLarge += 1;
            skipped.push({ folder: plan.folder, file: media.fileName, reason: 'too large' });
            continue;
          }
          jobs.push({ entryId, path: `${plan.folder}${media.fileName}`, bytes: media.plaintextBytes, kind: 'media', plan, fileName: media.fileName, mediaId: media.mediaId });
        }
      }
      const parts = planParts(jobs, destination.partMaxBytes, EXPORT_PART_MAX_FILES) as readonly (readonly FileJob[])[];
      const exportDate = deps.clock.nowIso().slice(0, DATE_CHARS);
      let bytesWritten = 0;
      let mediaFiles = 0;
      const done = new Set<string>();
      for (const [partIndex, files] of parts.entries()) {
        check();
        sink = await destination.createPart(partFileName(exportDate, partIndex + 1));
        const writer = deps.createArchiveWriter(sink);
        if (partIndex === 0) {
          const readme = writer.addFile(EXPORT_README_FILE, { compress: false, mtime: new Date(deps.clock.now()) });
          await readme.push(UTF8.encode(EXPORT_TEXT.readme(exportDate)), true);
        }
        for (const job of files) {
          check();
          onProgress({ phase: 'writing', entriesDone: done.size, entriesTotal: entries.length, bytesWritten, partNumber: partIndex + 1, partsTotal: parts.length });
          if (job.kind === 'note') {
            const note = writer.addFile(job.path, { compress: true, mtime: job.plan.mtime });
            const bytes = UTF8.encode(job.plan.noteText);
            await note.push(bytes, true);
            bytesWritten += bytes.length;
          } else {
            onProgress({ phase: 'downloading', entriesDone: done.size, entriesTotal: entries.length, bytesWritten, partNumber: partIndex + 1, partsTotal: parts.length });
            const written = await writeMedia(job.entryId, job, writer);
            if (written === 'missing') skipped.push({ folder: job.plan.folder, file: job.fileName, reason: 'missing' });
            else {
              bytesWritten += written;
              mediaFiles += 1;
            }
          }
          if (job.kind === 'media' || job.plan.media.length === 0) {
            done.add(job.entryId);
            await deps.enforceCap();
          }
        }
        if (partIndex === parts.length - 1 && skipped.length > 0) {
          const missing = writer.addFile(EXPORT_MISSING_FILE, { compress: true, mtime: new Date(deps.clock.now()) });
          const lines = [EXPORT_TEXT.missingHeader, ...skipped.map((item) => EXPORT_TEXT.missingLine(item.folder, item.file, item.reason))];
          await missing.push(UTF8.encode(`${lines.join('\n')}\n`), true);
        }
        await writer.finish();
        check();
        await sink.close();
        sink = null;
      }
      onProgress({ phase: 'done', entriesDone: entries.length, entriesTotal: entries.length, bytesWritten, partNumber: parts.length, partsTotal: parts.length });
      return {
        parts: parts.length,
        entries: entries.length,
        mediaFiles,
        bytes: bytesWritten,
        skippedUnreadable,
        skippedMissing: skipped.filter((item) => item.reason === 'missing').length,
        skippedTooLarge,
      };
    } catch (error) {
      if (sink !== null) await sink.abort().catch(() => undefined);
      throw cancelled ? new ExportCancelledError() : error;
    } finally {
      deps.autoLock.resume();
    }
  }

  return {
    run,
    cancel: () => {
      cancelled = true;
    },
  };
}
