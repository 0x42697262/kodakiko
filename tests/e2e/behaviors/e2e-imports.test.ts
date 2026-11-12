import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const E2E_ROOT = join(import.meta.dirname, '..');
const SOURCE_DIRECTORIES = ['support', 'scenarios', 'behaviors'];
const SERVER_PATH_PATTERN = /from\s+['"][^'"]*apps\/server[^'"]*['"]/;
const SERVER_IMPORT_PATTERN = /from\s+['"](@kodakiko\/server[^'"]*)['"]/g;
const ALLOWED_SERVER_SPECIFIER = '@kodakiko/server/testing';

function sourceFiles(): string[] {
  return SOURCE_DIRECTORIES.flatMap((directory) =>
    readdirSync(join(E2E_ROOT, directory))
      .filter((name) => name.endsWith('.ts') && name !== 'e2e-imports.test.ts')
      .map((name) => join(E2E_ROOT, directory, name)),
  );
}

describe('e2e package server imports', () => {
  it('reaches server code only through @kodakiko/server/testing', () => {
    const offenders: string[] = [];
    for (const file of sourceFiles()) {
      const text = readFileSync(file, 'utf8');
      if (SERVER_PATH_PATTERN.test(text)) offenders.push(file);
      for (const match of text.matchAll(SERVER_IMPORT_PATTERN)) {
        if (match[1] !== ALLOWED_SERVER_SPECIFIER) offenders.push(file);
      }
    }
    expect(offenders).toEqual([]);
  });
});
