import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

const BUNDLES = [
  { entry: '../web/workers/argon2-worker.ts', outfile: '../public/workers/argon2.js' },
  { entry: '../web/sw/media-sw.ts', outfile: '../public/media-sw.js' },
] as const;

for (const bundle of BUNDLES) {
  await build({
    entryPoints: [fileURLToPath(new URL(bundle.entry, import.meta.url))],
    outfile: fileURLToPath(new URL(bundle.outfile, import.meta.url)),
    bundle: true,
    format: 'iife',
    platform: 'browser',
    minify: true,
  });
}
