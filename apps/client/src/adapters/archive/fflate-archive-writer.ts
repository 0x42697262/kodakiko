import { clampZipDate } from '@kodakiko/core';
import { Zip, ZipDeflate, ZipPassThrough } from 'fflate';
import type { ArchiveWriter, PartSink } from '../../services/export/archive';

const DEFLATE_LEVEL = 6;

export function createFflateArchiveWriter(sink: PartSink): ArchiveWriter {
  let pending: Promise<void> = Promise.resolve();
  let failure: Error | null = null;
  const zip = new Zip((error, chunk) => {
    if (error !== null) {
      failure = error;
      return;
    }
    const copy = chunk.slice();
    pending = pending.then(() => sink.write(copy));
  });

  async function settle(): Promise<void> {
    await pending;
    if (failure !== null) throw failure;
  }

  return {
    addFile: (name, options) => {
      const file = options.compress ? new ZipDeflate(name, { level: DEFLATE_LEVEL }) : new ZipPassThrough(name);
      file.mtime = clampZipDate(options.mtime);
      zip.add(file);
      return {
        push: async (bytes, final) => {
          file.push(bytes, final);
          await settle();
        },
      };
    },
    finish: async () => {
      zip.end();
      await settle();
    },
  };
}
