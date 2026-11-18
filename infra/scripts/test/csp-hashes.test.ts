import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { inlineScriptHashes, writeCspHashes } from '../csp-hashes.ts';

const FIRST = 'console.log("one")';
const SECOND = '\n  window.x = 2;\n';

function hashOf(text: string): string {
  return `'sha256-${createHash('sha256').update(text, 'utf8').digest('base64')}'`;
}

const FIXTURE = `<!doctype html><html><head>
<script>${FIRST}</script>
<script src="/viewer.js" defer></script>
</head><body><script type="module">${SECOND}</script></body></html>`;

let directory: string | null = null;

afterEach(async () => {
  if (directory !== null) await rm(directory, { recursive: true, force: true });
  directory = null;
});

describe('csp hashes', () => {
  it('hashes inline scripts only, in document order', () => {
    expect(inlineScriptHashes(FIXTURE)).toEqual([hashOf(FIRST), hashOf(SECOND)]);
  });

  it('writes one double-quoted env line for every html file of the export', async () => {
    directory = await mkdtemp(join(tmpdir(), 'csp-'));
    await mkdir(join(directory, 'nested'));
    await writeFile(join(directory, 'index.html'), FIXTURE);
    await writeFile(join(directory, 'nested', 'other.html'), `<script>${FIRST}</script>`);
    await writeFile(join(directory, 'app.js'), '<script>ignored()</script>');
    await writeCspHashes(directory);
    const line = await readFile(join(directory, 'csp-hashes.env'), 'utf8');
    expect(line).toBe(`CSP_SCRIPT_HASHES="${hashOf(FIRST)} ${hashOf(SECOND)}"\n`);
  });

  it('writes an empty value when there is no inline script', async () => {
    directory = await mkdtemp(join(tmpdir(), 'csp-'));
    await writeFile(join(directory, 'index.html'), '<script src="/a.js"></script>');
    await writeCspHashes(directory);
    expect(await readFile(join(directory, 'csp-hashes.env'), 'utf8')).toBe('CSP_SCRIPT_HASHES=""\n');
  });
});
