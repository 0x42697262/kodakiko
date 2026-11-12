import { build } from 'esbuild';

await build({
  entryPoints: ['android/run.ts'],
  outfile: 'android/dist/run.mjs',
  bundle: true,
  platform: 'node',
  format: 'esm',
  packages: 'bundle',
  external: ['libsodium-wrappers-sumo'],
  banner: {
    js: "import { createRequire } from 'node:module'; const require = createRequire(import.meta.url);",
  },
});
