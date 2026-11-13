import { createHash } from 'node:crypto';
import { readdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const SCRIPT_ELEMENT = /<script\b([^>]*)>([\s\S]*?)<\/script>/gi;
const SRC_ATTRIBUTE = /\bsrc\s*=/i;
const HTML_EXTENSION = '.html';
const ENV_FILE = 'csp-hashes.env';
const ENV_NAME = 'CSP_SCRIPT_HASHES';

export function inlineScriptHashes(html: string): string[] {
  const hashes: string[] = [];
  for (const match of html.matchAll(SCRIPT_ELEMENT)) {
    const attributes = match[1] ?? '';
    const body = match[2] ?? '';
    if (SRC_ATTRIBUTE.test(attributes)) continue;
    hashes.push(`'sha256-${createHash('sha256').update(body, 'utf8').digest('base64')}'`);
  }
  return hashes;
}

async function htmlFiles(directory: string): Promise<string[]> {
  const found: string[] = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) found.push(...(await htmlFiles(path)));
    else if (entry.name.endsWith(HTML_EXTENSION)) found.push(path);
  }
  return found.sort();
}

export async function writeCspHashes(directory: string): Promise<string> {
  const unique = new Set<string>();
  for (const file of await htmlFiles(directory)) {
    for (const hash of inlineScriptHashes(await readFile(file, 'utf8'))) unique.add(hash);
  }
  const line = `${ENV_NAME}="${[...unique].join(' ')}"\n`;
  await writeFile(join(directory, ENV_FILE), line);
  return line;
}

if (import.meta.main) {
  const directory = process.argv[2];
  if (directory === undefined) {
    process.stderr.write('usage: node infra/scripts/csp-hashes.ts <web export directory>\n');
    process.exit(1);
  }
  process.stdout.write(await writeCspHashes(directory));
}
