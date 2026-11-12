import { existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { WORKSPACE_MARKER } from './constants.ts';
import { STACK_TEXT } from './text.ts';

let cachedRoot: string | null = null;

export function repoRoot(): string {
  if (cachedRoot !== null) return cachedRoot;
  const start = process.cwd();
  let current = start;
  while (!existsSync(join(current, WORKSPACE_MARKER))) {
    const parent = dirname(current);
    if (parent === current) throw new Error(STACK_TEXT.workspaceRootMissing(start));
    current = parent;
  }
  cachedRoot = current;
  return current;
}

export function fromRoot(...segments: readonly string[]): string {
  return resolve(repoRoot(), ...segments);
}
