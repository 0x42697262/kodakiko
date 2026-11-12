import { lstat, readdir, realpath, rm } from 'node:fs/promises';
import { basename, dirname, join } from 'node:path';

const ANDROID_DIRECTORY = 'android';
const NODE_MODULES_DIRECTORY = 'node_modules';
const SCOPE_PREFIX = '@';
const PROJECT_BUILD_DIRECTORIES: readonly (readonly string[])[] = [
  [ANDROID_DIRECTORY, 'app', 'build'],
  [ANDROID_DIRECTORY, 'build'],
];
const PACKAGE_INTERMEDIATE_DIRECTORIES: readonly string[] = ['build', '.cxx'];

export type CleanResult = {
  readonly bytesFreed: number;
  readonly removed: readonly string[];
};

async function exists(path: string): Promise<boolean> {
  try {
    await lstat(path);
    return true;
  } catch {
    return false;
  }
}

async function sizeOf(path: string): Promise<number> {
  const info = await lstat(path);
  if (info.isSymbolicLink() || !info.isDirectory()) return info.size;
  let total = 0;
  for (const entry of await readdir(path)) total += await sizeOf(join(path, entry));
  return total;
}

async function directoryEntries(directory: string): Promise<readonly string[]> {
  try {
    return await readdir(directory);
  } catch {
    return [];
  }
}

async function packageDirectories(nodeModules: string): Promise<readonly string[]> {
  const found: string[] = [];
  for (const entry of await directoryEntries(nodeModules)) {
    const path = join(nodeModules, entry);
    if (entry.startsWith(SCOPE_PREFIX)) {
      for (const scoped of await directoryEntries(path)) found.push(join(path, scoped));
    } else {
      found.push(path);
    }
  }
  return found;
}

function containingNodeModules(packageRoot: string): string | null {
  const parent = dirname(packageRoot);
  const candidate = basename(parent).startsWith(SCOPE_PREFIX) ? dirname(parent) : parent;
  return basename(candidate) === NODE_MODULES_DIRECTORY ? candidate : null;
}

async function reachablePackages(clientRoot: string): Promise<ReadonlySet<string>> {
  const visitedPackages = new Set<string>();
  const visitedModules = new Set<string>();
  const pending = [join(clientRoot, NODE_MODULES_DIRECTORY)];
  while (pending.length > 0) {
    const nodeModules = pending.pop() ?? '';
    if (!(await exists(nodeModules))) continue;
    const resolvedModules = await realpath(nodeModules);
    if (visitedModules.has(resolvedModules)) continue;
    visitedModules.add(resolvedModules);
    for (const path of await packageDirectories(resolvedModules)) {
      let resolved: string;
      try {
        resolved = await realpath(path);
      } catch {
        continue;
      }
      if (visitedPackages.has(resolved)) continue;
      visitedPackages.add(resolved);
      pending.push(join(resolved, NODE_MODULES_DIRECTORY));
      const siblings = containingNodeModules(resolved);
      if (siblings !== null) pending.push(siblings);
    }
  }
  return visitedPackages;
}

async function removeAll(paths: readonly string[]): Promise<CleanResult> {
  let bytesFreed = 0;
  const removed: string[] = [];
  for (const path of paths) {
    if (!(await exists(path))) continue;
    bytesFreed += await sizeOf(path);
    await rm(path, { recursive: true, force: true });
    removed.push(path);
  }
  return { bytesFreed, removed };
}

export async function cleanAndroid(clientRoot: string): Promise<CleanResult> {
  const targets = PROJECT_BUILD_DIRECTORIES.map((parts) => join(clientRoot, ...parts));
  for (const packageRoot of await reachablePackages(clientRoot)) {
    for (const name of PACKAGE_INTERMEDIATE_DIRECTORIES) {
      targets.push(join(packageRoot, ANDROID_DIRECTORY, name));
    }
  }
  return removeAll(targets);
}
