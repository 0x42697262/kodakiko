import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ANDROID = join(HERE, '..', '..');
const REPO = join(ANDROID, '..', '..');
const SOURCE = join(REPO, 'docs', 'dependencies.md');
const LOCKFILE = join(ANDROID, 'app', 'gradle.lockfile');
const TARGET = join(ANDROID, 'app', 'src', 'main', 'res', 'raw', 'open_source_licenses.json');
const SECTION_START = '## Kotlin client allowlist';
const SECTION_END = /^## /;
const SHIPPED_CONFIGURATION = 'releaseRuntimeClasspath';
const ARTIFACT_HEADER = 'Artifact';
const LICENSE_HEADER = 'License';
const SCHEMA_VERSION = 1;
const JSON_INDENT = 2;
const CODE_SPAN = /`([^`]+)`/g;
const COORDINATE = /^([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+)$/;
const SUFFIX = /^-[A-Za-z0-9_.-]+$/;
const BARE_ARTIFACT = /^[A-Za-z0-9_.-]+$/;
const LAST_SEGMENT = /-[^-]+$/;
const TABLE_SEPARATOR = /^\|\s*-/;
const TRANSITIVE_ROW = /^transitive:/;

type License = { readonly name: string; readonly version: string; readonly license: string };

function cells(line: string): string[] {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((cell) => cell.trim());
}

function kotlinSection(markdown: string): string[] {
  const lines = markdown.split('\n');
  const start = lines.findIndex((line) => line.startsWith(SECTION_START));
  if (start < 0) throw new Error(`docs/dependencies.md has no "${SECTION_START}" section`);
  const end = lines.findIndex((line, index) => index > start && SECTION_END.test(line));
  return lines.slice(start + 1, end < 0 ? lines.length : end);
}

function coordinates(artifactCell: string): string[] {
  const found: string[] = [];
  let group: string | null = null;
  let artifact: string | null = null;
  for (const match of artifactCell.matchAll(CODE_SPAN)) {
    const token = match[1].trim();
    const full = COORDINATE.exec(token);
    if (full !== null) {
      group = full[1];
      artifact = full[2];
    } else if (SUFFIX.test(token) && group !== null && artifact !== null) {
      artifact = artifact.replace(LAST_SEGMENT, '') + token;
    } else if (BARE_ARTIFACT.test(token) && group !== null) {
      artifact = token;
    } else {
      continue;
    }
    found.push(`${group}:${artifact}`);
  }
  return found;
}

function shippedVersions(lockfile: string): Map<string, string> {
  const versions = new Map<string, string>();
  for (const line of lockfile.split('\n')) {
    const [coordinate, configurations] = line.split('=');
    if (configurations === undefined || !configurations.split(',').includes(SHIPPED_CONFIGURATION)) continue;
    const parts = coordinate.split(':');
    if (parts.length === 3) versions.set(`${parts[0]}:${parts[1]}`, parts[2]);
  }
  return versions;
}

function licenses(markdown: string, lockfile: string): License[] {
  const versions = shippedVersions(lockfile);
  const result = new Map<string, License>();
  let artifactColumn = -1;
  let licenseColumn = -1;
  const lines = kotlinSection(markdown);
  lines.forEach((line, index) => {
    if (!line.startsWith('|')) {
      artifactColumn = -1;
      licenseColumn = -1;
      return;
    }
    if (TABLE_SEPARATOR.test(line)) return;
    if (TABLE_SEPARATOR.test(lines[index + 1] ?? '')) {
      const header = cells(line);
      artifactColumn = header.indexOf(ARTIFACT_HEADER);
      licenseColumn = header.indexOf(LICENSE_HEADER);
      return;
    }
    if (artifactColumn < 0 || licenseColumn < 0) return;
    const row = cells(line);
    const license = row[licenseColumn];
    const transitive = TRANSITIVE_ROW.test(row[artifactColumn]);
    for (const name of coordinates(row[artifactColumn])) {
      const version = versions.get(name);
      if (version === undefined || (transitive && result.has(name))) continue;
      result.set(name, { name, version, license });
    }
  });
  return [...result.values()].sort((left, right) => (left.name < right.name ? -1 : left.name > right.name ? 1 : 0));
}

const list = licenses(readFileSync(SOURCE, 'utf8'), readFileSync(LOCKFILE, 'utf8'));
const json = `${JSON.stringify({ schemaVersion: SCHEMA_VERSION, licenses: list }, null, JSON_INDENT)}\n`;

if (process.argv.includes('--check')) {
  let current = '';
  try {
    current = readFileSync(TARGET, 'utf8');
  } catch {
    current = '';
  }
  if (current !== json) {
    process.stderr.write('open_source_licenses.json is out of date; run node apps/android/tools/licenses/emit-licenses.ts\n');
    process.exit(1);
  }
} else {
  mkdirSync(dirname(TARGET), { recursive: true });
  writeFileSync(TARGET, json);
}
process.stdout.write(`${list.length} licenses\n`);
