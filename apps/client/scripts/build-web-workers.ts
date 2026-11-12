import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

const ENTRY = fileURLToPath(new URL('../web/workers/argon2-worker.ts', import.meta.url));
const OUTFILE = fileURLToPath(new URL('../public/workers/argon2.js', import.meta.url));

await build({
  entryPoints: [ENTRY],
  outfile: OUTFILE,
  bundle: true,
  format: 'iife',
  platform: 'browser',
  minify: true,
});
