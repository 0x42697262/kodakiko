import { build } from 'esbuild';
import { mkdir, rm, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { getAppPage, viewerPage } from './src/pages.ts';

export type BuildOptions = { readonly outdir: string };
export type BuildReport = {
  readonly inputs: readonly string[];
  readonly outputs: readonly string[];
};

const PACKAGE_DIRECTORY = fileURLToPath(new URL('.', import.meta.url));
const DEFAULT_OUTDIR = join(PACKAGE_DIRECTORY, 'dist');
const ENTRY_POINTS = Object.freeze({
  viewer: 'src/main.ts',
  'media-sw': 'src/sw/media-sw.ts',
  styles: 'src/styles.css',
});
const PAGES = Object.freeze({ 'index.html': getAppPage, 'viewer.html': viewerPage });
const BROWSER_TARGETS = ['chrome109', 'firefox115', 'safari16'];

export async function buildLinkViewer(options: BuildOptions): Promise<BuildReport> {
  const outdir = resolve(options.outdir);
  await rm(outdir, { recursive: true, force: true });
  await mkdir(outdir, { recursive: true });
  const result = await build({
    absWorkingDir: PACKAGE_DIRECTORY,
    entryPoints: ENTRY_POINTS,
    outdir,
    bundle: true,
    format: 'iife',
    platform: 'browser',
    target: BROWSER_TARGETS,
    minify: true,
    metafile: true,
    legalComments: 'none',
    define: { 'import.meta.url': 'self.location.href' },
    logLevel: 'warning',
  });
  for (const [name, render] of Object.entries(PAGES)) await writeFile(join(outdir, name), render());
  return {
    inputs: Object.keys(result.metafile.inputs),
    outputs: [...Object.keys(result.metafile.outputs), ...Object.keys(PAGES)],
  };
}

if (
  process.argv[1] !== undefined &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  await buildLinkViewer({ outdir: DEFAULT_OUTDIR });
}
