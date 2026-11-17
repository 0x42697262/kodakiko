import { mkdir, mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { buildLinkViewer } from '../build.ts';
import { ANDROID_RELEASES_URL } from '../src/keys.ts';
import { TEXT } from '../src/text.ts';

const BUILD_TIMEOUT_MS = 120_000;
const MAX_FILE_BYTES = 1_310_720;
const MAX_TOTAL_BYTES = 2_621_440;
const MAX_TOTAL_GZIP_BYTES = 819_200;
const REPORT_FILE = fileURLToPath(new URL('../../../tmp/link-viewer-bundle-size.json', import.meta.url));
const FORBIDDEN_INPUT = /(^|\/)node_modules\/(\.pnpm\/)?(react|react-dom|react-native[^/]*|expo[^/]*|@expo|marked|idb)[@/]/;
const CLIENT_INPUT = /(^|\/)apps\/client\//;

type FileSize = { readonly path: string; readonly bytes: number; readonly gzipBytes: number };

let outdir = '';
let inputs: readonly string[] = [];
let sizes: readonly FileSize[] = [];

async function listFiles(directory: string): Promise<string[]> {
  const found: string[] = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) found.push(...(await listFiles(path)));
    else found.push(path);
  }
  return found;
}

async function text(name: string): Promise<string> {
  return readFile(join(outdir, name), 'utf8');
}

function scriptTags(html: string): string[] {
  return [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].map((match) => match[0]);
}

function referencedAssets(html: string): string[] {
  return [...html.matchAll(/\b(?:src|href)="(\/[^"#?]+)"/g)].map((match) => match[1] ?? '').filter((path) => path !== '/');
}

beforeAll(async () => {
  outdir = await mkdtemp(join(tmpdir(), 'link-viewer-'));
  const report = await buildLinkViewer({ outdir });
  inputs = report.inputs;
  const files = await listFiles(outdir);
  sizes = await Promise.all(
    files.map(async (path) => {
      const bytes = await readFile(path);
      return { path: relative(outdir, path), bytes: (await stat(path)).size, gzipBytes: gzipSync(bytes).length };
    }),
  );
  const totalBytes = sizes.reduce((sum, file) => sum + file.bytes, 0);
  const totalGzipBytes = sizes.reduce((sum, file) => sum + file.gzipBytes, 0);
  const record = { files: [...sizes].sort((left, right) => left.path.localeCompare(right.path)), totalBytes, totalGzipBytes };
  await mkdir(join(REPORT_FILE, '..'), { recursive: true });
  await writeFile(REPORT_FILE, `${JSON.stringify(record, null, 2)}\n`);
  console.info(`link viewer bundle: ${totalBytes} bytes, ${totalGzipBytes} bytes gzip (${REPORT_FILE})`);
}, BUILD_TIMEOUT_MS);

afterAll(async () => {
  if (outdir !== '') await rm(outdir, { recursive: true, force: true });
});

describe('link viewer build', () => {
  it('emits the Get the Android app page, the viewer page and the media service worker at the root', () => {
    const paths = sizes.map((file) => file.path);
    expect(paths).toContain('index.html');
    expect(paths).toContain('viewer.html');
    expect(paths).toContain('media-sw.js');
  });

  it('records the bundle size and stays within the budget', async () => {
    const recorded = JSON.parse(await readFile(REPORT_FILE, 'utf8')) as { totalBytes: number; totalGzipBytes: number };
    expect(recorded.totalBytes).toBeGreaterThan(0);
    for (const file of sizes) expect(file.bytes, file.path).toBeLessThanOrEqual(MAX_FILE_BYTES);
    expect(recorded.totalBytes).toBeLessThanOrEqual(MAX_TOTAL_BYTES);
    expect(recorded.totalGzipBytes).toBeLessThanOrEqual(MAX_TOTAL_GZIP_BYTES);
  });

  it('bundles the TS core and libsodium-wrappers-sumo, and nothing from React, Expo or the old client', () => {
    expect(inputs.length).toBeGreaterThan(0);
    expect(inputs.some((input) => input.includes('libsodium-wrappers-sumo'))).toBe(true);
    expect(inputs.some((input) => input.includes('packages/core/src/'))).toBe(true);
    expect(inputs.filter((input) => FORBIDDEN_INPUT.test(input))).toEqual([]);
    expect(inputs.filter((input) => CLIENT_INPUT.test(input))).toEqual([]);
  });

  it('serves pages that run under the Caddy CSP: external scripts only, no inline handlers', async () => {
    for (const page of ['viewer.html', 'index.html']) {
      const html = await text(page);
      for (const tag of scriptTags(html)) {
        expect(tag, page).toMatch(/\bsrc="\//);
        expect(tag.replace(/<script\b[^>]*>/i, '').replace(/<\/script>/i, '').trim(), page).toBe('');
      }
      expect(html, page).not.toMatch(/\son[a-z]+=/i);
      expect(html, page).not.toMatch(/javascript:/i);
      expect(html, page).toMatch(/<meta\s+name="viewport"/i);
    }
  });

  it('references only files that exist in the build, by absolute path (the viewer lives under /s/)', async () => {
    const paths = new Set(sizes.map((file) => `/${file.path}`));
    for (const page of ['viewer.html', 'index.html']) {
      for (const asset of referencedAssets(await text(page))) expect(paths.has(asset), `${page} → ${asset}`).toBe(true);
    }
    expect(scriptTags(await text('viewer.html')).length).toBeGreaterThan(0);
    expect(await text('viewer.html')).not.toMatch(/\b(?:src|href)="(?!\/|https:\/\/|#)[^"]+"/);
  });

  it('keeps the viewer out of search engines', async () => {
    expect(await text('viewer.html')).toMatch(/<meta\s+name="robots"\s+content="noindex/i);
  });

  it('makes the root page a static Get the Android app page with the releases link', async () => {
    const html = await text('index.html');
    expect(scriptTags(html)).toEqual([]);
    expect(html).toContain(TEXT.getAppHeading);
    expect(html).toContain(`href="${ANDROID_RELEASES_URL}"`);
    expect(html).toContain('data-testid="getApp.screen"');
    expect(html).toContain('data-testid="getApp.download"');
  });
});
